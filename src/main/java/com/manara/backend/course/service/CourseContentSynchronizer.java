package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.mapper.CourseModuleMapper;
import com.manara.backend.course.mapper.SubscriptionPlanMapper;
import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.TrackedContent;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.service.VideoMetadataService;
import com.manara.backend.video.service.VideoProviderResolver;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.service.QuizService;
import com.manara.backend.quiz.service.QuizSyncResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private final CourseEntitlementRepository courseEntitlementRepository;
    private final CourseSubscriptionRepository courseSubscriptionRepository;
    private final Clock clock;
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

        // Carried on the state so the lesson pass can name the module a lesson is leaving without
        // initialising a proxy for it — and without failing for a module deleted later in this pass.
        SyncState state = new SyncState(modulesById);

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
            recordQuizSync(changes,
                    quizService.sync(attachment.ownerType(), attachment.ownerId().get(), attachment.request()));
        }
        recordQuizSync(changes, quizService.sync(QuizOwnerType.COURSE, course.getId(), request.getFinalQuiz()));

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
                changes.of(module).created();
                slots.add(SiblingOrdering.Slot.unplaced(module));
            } else {
                module = resolveOwnChild(modulesById, seen, request.getId(),
                        "error.course.moduleNotInCourse", "error.course.moduleDuplicate");
                changes.of(module)
                        .metadata(module.getTitle(), request.getTitle().trim(), module::setTitle)
                        .content(module.getDescription(), trimToNull(request.getDescription()),
                                module::setDescription);
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
                changes.of(lesson).created();
                slots.add(SiblingOrdering.Slot.unplaced(lesson));
            } else {
                lesson = resolveOwnChild(lessonsById, state.seenLessonIds, request.getId(),
                        "error.course.lessonNotInCourse", "error.course.lessonDuplicate");

                VideoSource storedVideo = lesson.getVideo();
                VideoSource nextVideo = nextVideoFor(storedVideo, request);

                boolean videoChanged = !nextVideo.getUrl().equals(storedVideo.getUrl());
                changes.of(lesson)
                        .metadata(lesson.getTitle(), request.getTitle().trim(), lesson::setTitle)
                        .metadata(lesson.getSummary(), request.getSummary(), lesson::setSummary)
                        .content(lesson.getDescription(), request.getDescription(), lesson::setDescription)
                        .content(storedVideo, nextVideo, lesson::setVideo);
                // Re-parenting is how a structure switch keeps a lesson the payload still wants.
                // Compared by id rather than by entity: `lesson.getModule()` can be an uninitialised
                // proxy, whose id-based equals reads a field that is not there yet.
                Long currentModuleId = lesson.getModule() == null ? null : lesson.getModule().getId();
                Long nextModuleId = module == null ? null : module.getId();
                boolean reparented = !java.util.Objects.equals(currentModuleId, nextModuleId);
                if (reparented) {
                    // Read before the write, because "moved from Module 1" is the one fact about
                    // this change that stops existing the moment the new parent is assigned.
                    String from = currentModuleId == null
                            ? null
                            : titleOfModule(state.modulesById, currentModuleId);
                    lesson.setModule(module);
                    changes.of(lesson).moved(from);
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
     * The video to store for a lesson the payload is updating.
     *
     * <p>Two cases, and the difference between them is the whole of the legacy-content fix.
     *
     * <p>A video the payload <strong>changed</strong> is resolved strictly, exactly as before: the
     * validator has already refused an unplayable one, so this cannot fail, and resolving is what
     * fills the provider columns for the new URL.
     *
     * <p>A video the payload is only <strong>carrying back</strong> is resolved leniently. It is
     * still re-derived when it can be — that is how a row written before the provider columns
     * existed gets them filled in by an ordinary save, which is behaviour worth keeping — but a URL
     * no adapter claims now keeps the source it already has instead of failing the save. That row
     * was accepted under the rules of its time and this request is not touching it; the read path
     * has always treated it that way, and this is the write path agreeing.
     *
     * @param stored  what the lesson currently holds, never null — {@code video_url} is NOT NULL
     * @param request the lesson as submitted
     */
    private VideoSource nextVideoFor(VideoSource stored, LessonRequest request) {
        if (stored.matches(request.getVideoUrl(), request.getVideoProvider())) {
            return videoProviderResolver.tryResolve(stored.getUrl())
                    .map(resolved -> resolved.toVideoSource(stored))
                    .orElse(stored);
        }
        return videoProviderResolver.resolve(request.getVideoUrl(), request.getVideoProvider())
                .toVideoSource(stored);
    }

    /**
     * Carries a nested quiz's own diff up to the course's recorder.
     *
     * <p>The quiz aggregate works out for itself whether its title moved or its questions did — it
     * is the only thing holding both versions — and reports which. Recording it against the quiz
     * rather than against the course is what lets a curriculum mark one exam updated instead of the
     * whole module it closes.
     */
    private void recordQuizSync(CourseContentChanges changes, QuizSyncResult result) {
        if (result.quiz() != null && result.changed()) {
            changes.of(result.quiz()).recordIf(true, result.outcome());
        }
    }

    /**
     * The title of the module a lesson is leaving, from the map already loaded for this pass.
     *
     * <p>Never a fresh lookup: the entity may be a lazy proxy whose title would force a select, and
     * a module deleted earlier in the same pass would no longer be there to find.
     */
    private String titleOfModule(Map<Long, CourseModule> modulesById, Long moduleId) {
        CourseModule module = modulesById.get(moduleId);
        return module == null ? null : module.getTitle();
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
    private <T extends TrackedContent> void applyPositions(
            List<SiblingOrdering.Slot<T>> slots, ToIntFunction<T> positionOf,
            BiConsumer<T, Integer> setPosition, CourseContentChanges changes) {
        List<T> resolved = SiblingOrdering.resolve(slots);
        for (int position = 0; position < resolved.size(); position++) {
            T entity = resolved.get(position);
            // Recorded as a reorder, which is the weakest description there is — so a sibling that
            // only shifted up because the lesson above it was deleted keeps whatever stronger thing
            // was already said about it, and a lesson that was created here stays "new" rather than
            // being downgraded to "moved position".
            changes.of(entity)
                    .reordered(positionOf.applyAsInt(entity), position, value -> setPosition.accept(entity, value));
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

        stale.forEach(lesson -> changes.of(lesson).removed());
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

        stale.forEach(module -> changes.of(module).removed());
        quizService.deleteByOwners(QuizOwnerType.MODULE, stale.stream().map(CourseModule::getId).toList());
        courseModuleRepository.deleteAll(stale);
        courseModuleRepository.flush();
    }

    /**
     * Plans are only meaningful for subscription courses, so any other access type clears them.
     * A subscription course whose payload omits the collection keeps what it has — that is the
     * same "absent means untouched" rule the content tree follows.
     *
     * <h4>Plans are commerce, not curriculum</h4>
     * Nothing here records a content change, and this method is deliberately not given the recorder
     * to record into. A plan is what a course costs and for how long — renaming it, repricing it,
     * adding a cheaper tier or withdrawing one changes what a <em>future</em> buyer is offered and
     * changes nothing whatsoever about what an existing learner is studying.
     *
     * <p>It used to record all four. An instructor adjusting next quarter's pricing therefore told
     * every enrolled student that their course had been updated, and sent them looking through a
     * curriculum in which nothing had moved. Existing subscribers are unaffected in the way that
     * matters too: what they were charged is on their own {@code CourseSubscription} row, and their
     * access window is on their {@code CourseEntitlement}, neither of which is re-read against a
     * plan's current price.
     */
    /**
     * Brings the course's <em>offer</em> in line with the payload, without touching what anyone
     * already bought.
     *
     * <p>Plans are matched by id and edited in place, exactly as before: renaming, re-pricing or
     * shortening a plan changes what the next buyer gets and never the term an existing subscriber
     * paid for, which lives on their own entitlement and subscription rows.
     *
     * <p>What changed is removal. A plan the payload no longer mentions used to be deleted
     * outright, and the foreign keys from {@code course_entitlements} and
     * {@code course_subscriptions} refused — so an instructor with even one subscriber could
     * neither drop a plan nor take the course off {@code SUBSCRIPTION}, permanently, and all they
     * were told was that the request conflicted with existing data. A plan nobody has bought is
     * still deleted, because it is only ever an offer. One somebody has bought is
     * <strong>retired</strong>: off the offer, out of the editor, unbuyable, and still there for
     * every record written against it.
     */
    private void syncSubscriptionPlans(Course course, CourseRequest request, ResolvedCourseSettings settings,
                                       CourseContentChanges changes) {
        List<SubscriptionPlanRequest> requests = request.getSubscriptionPlans();
        boolean subscription = settings.accessType() == CourseAccessType.SUBSCRIPTION;

        if (subscription && requests == null) {
            return;
        }

        List<SubscriptionPlanRequest> effective = subscription && requests != null ? requests : List.of();
        // The offer, not the history: a retired plan is not editable and not re-orderable, and an id
        // naming one is refused as not belonging to this course's offer.
        List<SubscriptionPlan> persisted =
                subscriptionPlanRepository.findByCourseIdAndRetiredAtIsNullOrderByOrderIndexAsc(course.getId());
        Map<Long, SubscriptionPlan> plansById = indexById(persisted, SubscriptionPlan::getId);

        Set<Long> seen = new HashSet<>();
        Set<Long> retained = new HashSet<>();
        boolean changed = false;

        for (int order = 0; order < effective.size(); order++) {
            SubscriptionPlanRequest planRequest = effective.get(order);
            SubscriptionPlan plan;

            if (planRequest.getId() == null) {
                plan = subscriptionPlanRepository.save(
                        subscriptionPlanMapper.toSubscriptionPlan(planRequest, course, order));
                changed = true;
            } else {
                plan = resolveOwnChild(plansById, seen, planRequest.getId(),
                        "error.course.planNotInCourse", "error.course.planDuplicate");
                changed |= differs(plan, planRequest, order);
                plan.setName(planRequest.getName().trim());
                plan.setDuration(planRequest.getDuration());
                plan.setUnit(planRequest.getUnit());
                plan.setPrice(planRequest.getPrice());
                plan.setOrderIndex(order);
            }
            retained.add(plan.getId());
        }

        List<SubscriptionPlan> withdrawn = persisted.stream()
                .filter(plan -> !retained.contains(plan.getId()))
                .toList();
        changed |= withdraw(withdrawn);

        // A plan change is real and is deliberately not news: it moves the aggregate revision so a
        // stale tab cannot put the old offer back, and never the learner-facing content version.
        if (changed) {
            changes.recordUnannouncedChange();
        }
    }

    /**
     * Takes plans off the offer: deleted when they are only an offer, retired when they are not.
     *
     * @return whether anything was withdrawn
     */
    private boolean withdraw(List<SubscriptionPlan> withdrawn) {
        if (withdrawn.isEmpty()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<SubscriptionPlan> deletable = new ArrayList<>();

        for (SubscriptionPlan plan : withdrawn) {
            if (isReferencedByHistory(plan)) {
                plan.retire(now);
            } else {
                deletable.add(plan);
            }
        }
        if (!deletable.isEmpty()) {
            subscriptionPlanRepository.deleteAll(deletable);
        }
        return true;
    }

    /**
     * Whether anybody's access or purchase record points at this plan.
     *
     * <p>Both tables, not just the entitlement. An entitlement moves forward on renewal and can end
     * up naming a newer plan, while the {@code course_subscriptions} row that recorded the earlier
     * term still names the older one — deleting it would erase what that learner was actually sold.
     */
    private boolean isReferencedByHistory(SubscriptionPlan plan) {
        return courseEntitlementRepository.existsBySubscriptionPlanId(plan.getId())
                || courseSubscriptionRepository.existsByPlanId(plan.getId());
    }

    /** Whether the payload asks for a plan to differ from the one stored, position included. */
    private boolean differs(SubscriptionPlan plan, SubscriptionPlanRequest request, int order) {
        return !Objects.equals(plan.getName(), trimToNull(request.getName()))
                || !Objects.equals(plan.getDuration(), request.getDuration())
                || plan.getUnit() != request.getUnit()
                || !Objects.equals(plan.getOrderIndex(), order)
                || !sameAmount(plan.getPrice(), request.getPrice());
    }

    private static boolean sameAmount(BigDecimal current, BigDecimal next) {
        if (current == null || next == null) {
            return current == next;
        }
        // compareTo, not equals: 100 re-sent as 100.00 is the same price.
        return current.compareTo(next) == 0;
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

        /** The course's modules as they were when this pass began, by id. */
        private final Map<Long, CourseModule> modulesById;

        private SyncState(Map<Long, CourseModule> modulesById) {
            this.modulesById = modulesById;
        }

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
