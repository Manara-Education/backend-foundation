package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.mapper.CourseModuleMapper;
import com.manara.backend.course.mapper.SubscriptionPlanMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.video.model.ResolvedVideo;
import com.manara.backend.video.service.VideoMetadataService;
import com.manara.backend.video.service.VideoProviderResolver;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Brings a course's stored content in line with a submitted payload.
 *
 * <h2>Synchronization, not append</h2>
 * Nested children are matched by id: a child that carries one is updated in place, a child without
 * one is created, and a persisted child the payload no longer mentions is deleted. Ids stay stable
 * across edits, which is what future quiz attempts, analytics and auditing will hang off.
 *
 * <h2>Ownership</h2>
 * An incoming id is only ever resolved against the children this course already owns. An id from
 * another instructor's course is not "not found by accident" — it is unreachable, because it was
 * never in the lookup map. Nothing here trusts a client-supplied parent id.
 *
 * <h2>Absent versus empty</h2>
 * An absent content collection ({@code null}) means "leave the content alone" and an empty one
 * ({@code []}) means "remove everything". Without that distinction the previous metadata-only
 * update — which sent no lessons at all — would silently delete a whole course's content.
 * Changing structure while sending no content is rejected outright rather than guessed at.
 *
 * <h2>Order is not this class's to rewrite</h2>
 * Positions of siblings that already exist are left exactly as the database holds them. An
 * aggregate save carries the whole course, so its arrays are only as fresh as the tab that built
 * them, and letting them dictate order meant any save could silently undo a reorder made
 * elsewhere. Reordering has three commands of its own now — modules, root lessons, one module's
 * lessons — and they are the only way an instructor-initiated reorder is expressed. What is still
 * decided here is what {@link SiblingOrdering} decides: where a newly created or re-parented child
 * lands, and how the remaining positions close up after a deletion.
 *
 * <h2>Structure switches</h2>
 * There is no special path for them. Lessons are diffed against every lesson of the course
 * regardless of current parent, so a lesson the payload still references is re-parented and kept,
 * and one it drops is deleted along with its quiz and its learners' progress rows. Switching to
 * {@code FLAT} therefore removes the modules, and switching to {@code MODULES} removes the
 * lessons that were left behind — no leftovers, and nothing silently destroyed that the payload
 * still mentions.
 */
@Component
@RequiredArgsConstructor
public class CourseContentSynchronizer {

    private final CourseModuleRepository courseModuleRepository;
    private final LessonRepository lessonRepository;
    private final CompletedLessonRepository completedLessonRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CourseModuleMapper courseModuleMapper;
    private final LessonMapper lessonMapper;
    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final QuizService quizService;
    private final VideoMetadataService videoMetadataService;
    private final VideoProviderResolver videoProviderResolver;

    public void sync(Course course, CourseRequest request, ResolvedCourseSettings settings,
                     CourseContentChanges changes) {
        if (request.carriesContentFor(settings.structure())) {
            syncContent(course, request, settings.structure(), changes);
        }
        syncSubscriptionPlans(course, request, settings, changes);
    }

    private void syncContent(Course course, CourseRequest request, CourseStructure structure,
                             CourseContentChanges changes) {
        List<CourseModule> persistedModules = courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId());
        List<Lesson> persistedLessons = lessonRepository.findCourseLessonsInReadingOrder(course.getId());

        Map<Long, CourseModule> modulesById = indexById(persistedModules, CourseModule::getId);
        Map<Long, Lesson> lessonsById = indexById(persistedLessons, Lesson::getId);

        SyncState state = new SyncState();

        if (structure == CourseStructure.MODULES) {
            syncModules(course, request.getModules(), modulesById, lessonsById, state, changes);
        } else {
            syncLessons(course, null, request.getLessons(), lessonsById, state, changes);
        }

        removeStaleLessons(persistedLessons, state.retainedLessonIds, changes);
        removeStaleModules(persistedModules, state.retainedModuleIds, changes);

        // After the removals, so "close the gap a deleted sibling left" is literally what happens
        // rather than something that has to be arranged for separately.
        applyPositions(state.moduleOrdering, CourseModule::getOrderIndex, CourseModule::setOrderIndex, changes);
        for (List<SiblingOrdering.Slot<Lesson>> scope : state.lessonOrderings) {
            applyPositions(scope, Lesson::getOrderIndex, Lesson::setOrderIndex, changes);
        }

        // Newly created modules and lessons need their generated ids before a quiz can name them
        // as its owner.
        lessonRepository.flush();

        for (QuizAttachment attachment : state.quizAttachments) {
            changes.recordIf(quizService
                    .sync(attachment.ownerType(), attachment.ownerId().get(), attachment.request())
                    .changed());
        }
        changes.recordIf(quizService
                .sync(QuizOwnerType.COURSE, course.getId(), request.getFinalQuiz())
                .changed());

        refreshDurations(course, state.videoRefreshTargets);
    }

    private void syncModules(Course course, List<ModuleRequest> requests, Map<Long, CourseModule> modulesById,
                             Map<Long, Lesson> lessonsById, SyncState state, CourseContentChanges changes) {
        Set<Long> seen = new HashSet<>();
        List<SiblingOrdering.Slot<CourseModule>> slots = new ArrayList<>(requests.size());

        for (int order = 0; order < requests.size(); order++) {
            ModuleRequest request = requests.get(order);
            CourseModule module;

            if (request.getId() == null) {
                // Saved eagerly so the lessons created underneath it have a parent id to point at.
                // The position given here is provisional — the ordering pass below decides the real
                // one — but it has to be something, and the array index is as good a placeholder as
                // any. The per-course uniqueness constraint is deferred to COMMIT precisely so a
                // placeholder that collides with a sibling's position is not an error before then.
                module = courseModuleRepository.save(courseModuleMapper.toCourseModule(request, course, order));
                changes.record();
                slots.add(SiblingOrdering.Slot.unplaced(module));
            } else {
                module = resolveOwnChild(modulesById, seen, request.getId(),
                        "error.course.moduleNotInCourse", "error.course.moduleDuplicate");
                changes.set(module.getTitle(), request.getTitle().trim(), module::setTitle);
                changes.set(module.getDescription(), trimToNull(request.getDescription()), module::setDescription);
                slots.add(SiblingOrdering.Slot.stored(module, module.getOrderIndex()));
            }

            state.retainedModuleIds.add(module.getId());
            syncLessons(course, module, request.getLessons(), lessonsById, state, changes);
            state.quizAttachments.add(new QuizAttachment(QuizOwnerType.MODULE, module::getId, request.getQuiz()));
        }

        // Deferred to here rather than done in the loop: a module's final position depends on where
        // every one of its siblings ended up, which is not known until the last one has been read.
        state.moduleOrdering = slots;
    }

    private void syncLessons(Course course, CourseModule module, List<LessonRequest> requests,
                             Map<Long, Lesson> lessonsById, SyncState state, CourseContentChanges changes) {
        if (requests == null) {
            return;
        }

        List<SiblingOrdering.Slot<Lesson>> slots = new ArrayList<>(requests.size());

        for (int order = 0; order < requests.size(); order++) {
            LessonRequest request = requests.get(order);
            Lesson lesson;

            if (request.getId() == null) {
                lesson = lessonRepository.save(lessonMapper.toLesson(request, course, module, order));
                state.videoRefreshTargets.add(lesson);
                changes.record();
                slots.add(SiblingOrdering.Slot.unplaced(lesson));
            } else {
                lesson = resolveOwnChild(lessonsById, state.seenLessonIds, request.getId(),
                        "error.course.lessonNotInCourse", "error.course.lessonDuplicate");

                // The whole payload was already validated, so this cannot fail here; resolving
                // again is how the provider columns are kept in step with the URL on every save,
                // including for a lesson that predates them.
                ResolvedVideo video = videoProviderResolver.resolve(
                        request.getVideoUrl(), request.getVideoProvider());

                boolean videoChanged = !video.url().equals(lesson.getVideo().getUrl());
                changes.set(lesson.getTitle(), request.getTitle().trim(), lesson::setTitle);
                changes.set(lesson.getSummary(), request.getSummary(), lesson::setSummary);
                changes.set(lesson.getDescription(), request.getDescription(), lesson::setDescription);
                changes.set(lesson.getVideo(), video.toVideoSource(lesson.getVideo()), lesson::setVideo);
                // Re-parenting is how a structure switch keeps a lesson the payload still wants.
                // Compared by id rather than by entity: `lesson.getModule()` can be an uninitialised
                // proxy, whose id-based equals reads a field that is not there yet.
                Long currentModuleId = lesson.getModule() == null ? null : lesson.getModule().getId();
                Long nextModuleId = module == null ? null : module.getId();
                boolean reparented = !java.util.Objects.equals(currentModuleId, nextModuleId);
                if (reparented) {
                    lesson.setModule(module);
                    changes.record();
                }

                // A lesson arriving from another parent holds a position that belonged to a
                // different list, so it is placed from the payload like a brand-new one. A lesson
                // that stayed put keeps the position the database has for it, whatever order the
                // submitted array happened to be in.
                slots.add(reparented
                        ? SiblingOrdering.Slot.unplaced(lesson)
                        : SiblingOrdering.Slot.stored(lesson, lesson.getOrderIndex()));

                if (videoChanged) {
                    // Not a content change of its own — the duration is derived, and the real
                    // change (a different video) has already been recorded above.
                    lesson.setDuration(0);
                    state.videoRefreshTargets.add(lesson);
                }
            }

            state.retainedLessonIds.add(lesson.getId());
            state.quizAttachments.add(new QuizAttachment(QuizOwnerType.LESSON, lesson::getId, request.getQuiz()));
        }

        state.lessonOrderings.add(slots);
    }

    /**
     * Writes the positions {@link SiblingOrdering} resolved, contiguously from zero.
     *
     * <p>Compared before assigned like everything else on this path, so a scope that is already in
     * the resolved order writes nothing and records nothing. That is what keeps re-saving an
     * unchanged course out of the learners' "Updated" badge — and, now that stored siblings keep
     * their stored order, what makes an ordinary edit to a course whose order changed elsewhere
     * come out as a no-op here instead of a silent revert.
     */
    private <T> void applyPositions(List<SiblingOrdering.Slot<T>> slots, ToIntFunction<T> positionOf,
                                    BiConsumer<T, Integer> setPosition, CourseContentChanges changes) {
        List<T> resolved = SiblingOrdering.resolve(slots);
        for (int position = 0; position < resolved.size(); position++) {
            T entity = resolved.get(position);
            changes.set(positionOf.applyAsInt(entity), position, value -> setPosition.accept(entity, value));
        }
    }

    /**
     * Deletes lessons the payload dropped, together with everything that points at them: their
     * quiz, which no foreign key would have cleaned up, and their learners' completion rows, which
     * one would have blocked.
     */
    private void removeStaleLessons(List<Lesson> persisted, Set<Long> retainedIds,
                                    CourseContentChanges changes) {
        List<Lesson> stale = persisted.stream()
                .filter(lesson -> !retainedIds.contains(lesson.getId()))
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        changes.record();
        List<Long> staleIds = stale.stream().map(Lesson::getId).toList();
        quizService.deleteByOwners(QuizOwnerType.LESSON, staleIds);
        completedLessonRepository.deleteByLessonIdIn(staleIds);
        lessonRepository.deleteAll(stale);
        // Flushed here so the module deletes below cannot trip over a lesson still referencing them.
        lessonRepository.flush();
    }

    private void removeStaleModules(List<CourseModule> persisted, Set<Long> retainedIds,
                                    CourseContentChanges changes) {
        List<CourseModule> stale = persisted.stream()
                .filter(module -> !retainedIds.contains(module.getId()))
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        changes.record();
        quizService.deleteByOwners(QuizOwnerType.MODULE, stale.stream().map(CourseModule::getId).toList());
        courseModuleRepository.deleteAll(stale);
        courseModuleRepository.flush();
    }

    /**
     * Plans are only meaningful for subscription courses, so any other access type clears them.
     * A subscription course whose payload omits the collection keeps what it has — that is the
     * same "absent means untouched" rule the content tree follows.
     */
    private void syncSubscriptionPlans(Course course, CourseRequest request, ResolvedCourseSettings settings,
                                       CourseContentChanges changes) {
        List<SubscriptionPlanRequest> requests = request.getSubscriptionPlans();
        boolean subscription = settings.accessType() == CourseAccessType.SUBSCRIPTION;

        if (subscription && requests == null) {
            return;
        }

        List<SubscriptionPlanRequest> effective = subscription && requests != null ? requests : List.of();
        List<SubscriptionPlan> persisted = subscriptionPlanRepository.findByCourseIdOrderByOrderIndexAsc(course.getId());
        Map<Long, SubscriptionPlan> plansById = indexById(persisted, SubscriptionPlan::getId);

        Set<Long> seen = new HashSet<>();
        Set<Long> retained = new HashSet<>();

        for (int order = 0; order < effective.size(); order++) {
            SubscriptionPlanRequest planRequest = effective.get(order);
            SubscriptionPlan plan;

            if (planRequest.getId() == null) {
                plan = subscriptionPlanRepository.save(
                        subscriptionPlanMapper.toSubscriptionPlan(planRequest, course, order));
                changes.record();
            } else {
                plan = resolveOwnChild(plansById, seen, planRequest.getId(),
                        "error.course.planNotInCourse", "error.course.planDuplicate");
                changes.set(plan.getName(), planRequest.getName().trim(), plan::setName);
                changes.set(plan.getDuration(), planRequest.getDuration(), plan::setDuration);
                changes.set(plan.getUnit(), planRequest.getUnit(), plan::setUnit);
                // Compared by value: a scale-only difference between 100 and 100.00 is not a
                // price change, and BigDecimal.equals would call it one.
                changes.recordIf(!sameAmount(plan.getPrice(), planRequest.getPrice()));
                plan.setPrice(planRequest.getPrice());
                changes.set(plan.getOrderIndex(), order, plan::setOrderIndex);
            }
            retained.add(plan.getId());
        }

        List<SubscriptionPlan> stale = persisted.stream()
                .filter(plan -> !retained.contains(plan.getId()))
                .toList();
        if (!stale.isEmpty()) {
            changes.record();
            subscriptionPlanRepository.deleteAll(stale);
        }
    }

    /** {@code null}-safe numeric equality, so 100 and 100.00 are the same price. */
    private boolean sameAmount(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    /**
     * Duration is derived from the lessons, so it is recomputed after every content change rather
     * than trusted from the payload. New and re-pointed videos are measured out of band, exactly
     * as the standalone lesson endpoints do.
     */
    private void refreshDurations(Course course, List<Lesson> videoRefreshTargets) {
        lessonRepository.flush();
        course.setDuration(lessonRepository.sumDurationByCourseId(course.getId()));

        for (Lesson lesson : videoRefreshTargets) {
            videoMetadataService.refreshAsync(lesson.getId(), lesson.getVideo());
        }
    }

    /**
     * Resolves a nested id against this course's own children only — the single check that stops a
     * request from attaching content to, or stealing content from, another instructor's course.
     */
    private <T> T resolveOwnChild(Map<Long, T> ownChildrenById, Set<Long> seen, Long id,
                                  String notFoundCode, String duplicateCode) {
        if (!seen.add(id)) {
            throw new BusinessException(duplicateCode, id);
        }
        T child = ownChildrenById.get(id);
        if (child == null) {
            throw new BusinessException(notFoundCode, id);
        }
        return child;
    }

    private <T> Map<Long, T> indexById(List<T> entities, Function<T, Long> idAccessor) {
        Map<Long, T> byId = new HashMap<>();
        entities.forEach(entity -> byId.put(idAccessor.apply(entity), entity));
        return byId;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Mutable bookkeeping for one synchronization pass. */
    private static final class SyncState {
        private final Set<Long> retainedModuleIds = new HashSet<>();
        private final Set<Long> retainedLessonIds = new HashSet<>();
        private final Set<Long> seenLessonIds = new HashSet<>();
        private final List<QuizAttachment> quizAttachments = new ArrayList<>();
        private final List<Lesson> videoRefreshTargets = new ArrayList<>();

        /** The course's modules, awaiting the positions {@link SiblingOrdering} works out. */
        private List<SiblingOrdering.Slot<CourseModule>> moduleOrdering = List.of();

        /**
         * One entry per lesson scope the payload described — a flat course's single root list, or
         * one list per module. Kept apart rather than flattened: positions are per parent, so two
         * modules both starting at 0 is correct and merging them would be nonsense.
         */
        private final List<List<SiblingOrdering.Slot<Lesson>>> lessonOrderings = new ArrayList<>();
    }

    /**
     * A quiz waiting for its owner's id. Modules and lessons created in this pass only receive one
     * once they are flushed, so the owner is captured as a supplier and read afterwards.
     */
    private record QuizAttachment(QuizOwnerType ownerType, Supplier<Long> ownerId, QuizRequest request) {
    }
}
