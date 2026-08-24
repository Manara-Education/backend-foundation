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
import java.util.function.Function;
import java.util.function.Supplier;

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

    public void sync(Course course, CourseRequest request, ResolvedCourseSettings settings) {
        if (request.carriesContentFor(settings.structure())) {
            syncContent(course, request, settings.structure());
        }
        syncSubscriptionPlans(course, request, settings);
    }

    private void syncContent(Course course, CourseRequest request, CourseStructure structure) {
        List<CourseModule> persistedModules = courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId());
        List<Lesson> persistedLessons = lessonRepository.findCourseLessonsInReadingOrder(course.getId());

        Map<Long, CourseModule> modulesById = indexById(persistedModules, CourseModule::getId);
        Map<Long, Lesson> lessonsById = indexById(persistedLessons, Lesson::getId);

        SyncState state = new SyncState();

        if (structure == CourseStructure.MODULES) {
            syncModules(course, request.getModules(), modulesById, lessonsById, state);
        } else {
            syncLessons(course, null, request.getLessons(), lessonsById, state);
        }

        removeStaleLessons(persistedLessons, state.retainedLessonIds);
        removeStaleModules(persistedModules, state.retainedModuleIds);

        // Newly created modules and lessons need their generated ids before a quiz can name them
        // as its owner.
        lessonRepository.flush();

        for (QuizAttachment attachment : state.quizAttachments) {
            quizService.sync(attachment.ownerType(), attachment.ownerId().get(), attachment.request());
        }
        quizService.sync(QuizOwnerType.COURSE, course.getId(), request.getFinalQuiz());

        refreshDurations(course, state.videoRefreshTargets);
    }

    private void syncModules(Course course, List<ModuleRequest> requests, Map<Long, CourseModule> modulesById,
                             Map<Long, Lesson> lessonsById, SyncState state) {
        Set<Long> seen = new HashSet<>();

        for (int order = 0; order < requests.size(); order++) {
            ModuleRequest request = requests.get(order);
            CourseModule module;

            if (request.getId() == null) {
                // Saved eagerly so the lessons created underneath it have a parent id to point at.
                module = courseModuleRepository.save(courseModuleMapper.toCourseModule(request, course, order));
            } else {
                module = resolveOwnChild(modulesById, seen, request.getId(),
                        "error.course.moduleNotInCourse", "error.course.moduleDuplicate");
                module.setTitle(request.getTitle().trim());
                module.setDescription(trimToNull(request.getDescription()));
                module.setOrderIndex(order);
            }

            state.retainedModuleIds.add(module.getId());
            syncLessons(course, module, request.getLessons(), lessonsById, state);
            state.quizAttachments.add(new QuizAttachment(QuizOwnerType.MODULE, module::getId, request.getQuiz()));
        }
    }

    private void syncLessons(Course course, CourseModule module, List<LessonRequest> requests,
                             Map<Long, Lesson> lessonsById, SyncState state) {
        if (requests == null) {
            return;
        }

        for (int order = 0; order < requests.size(); order++) {
            LessonRequest request = requests.get(order);
            Lesson lesson;

            if (request.getId() == null) {
                lesson = lessonRepository.save(lessonMapper.toLesson(request, course, module, order));
                state.videoRefreshTargets.add(lesson);
            } else {
                lesson = resolveOwnChild(lessonsById, state.seenLessonIds, request.getId(),
                        "error.course.lessonNotInCourse", "error.course.lessonDuplicate");

                // The whole payload was already validated, so this cannot fail here; resolving
                // again is how the provider columns are kept in step with the URL on every save,
                // including for a lesson that predates them.
                ResolvedVideo video = videoProviderResolver.resolve(
                        request.getVideoUrl(), request.getVideoProvider());

                boolean videoChanged = !video.url().equals(lesson.getVideo().getUrl());
                lesson.setTitle(request.getTitle().trim());
                lesson.setSummary(request.getSummary());
                lesson.setDescription(request.getDescription());
                lesson.setVideo(video.toVideoSource());
                lesson.setOrderIndex(order);
                // Re-parenting is how a structure switch keeps a lesson the payload still wants.
                lesson.setModule(module);

                if (videoChanged) {
                    lesson.setDuration(0);
                    state.videoRefreshTargets.add(lesson);
                }
            }

            state.retainedLessonIds.add(lesson.getId());
            state.quizAttachments.add(new QuizAttachment(QuizOwnerType.LESSON, lesson::getId, request.getQuiz()));
        }
    }

    /**
     * Deletes lessons the payload dropped, together with everything that points at them: their
     * quiz, which no foreign key would have cleaned up, and their learners' completion rows, which
     * one would have blocked.
     */
    private void removeStaleLessons(List<Lesson> persisted, Set<Long> retainedIds) {
        List<Lesson> stale = persisted.stream()
                .filter(lesson -> !retainedIds.contains(lesson.getId()))
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        List<Long> staleIds = stale.stream().map(Lesson::getId).toList();
        quizService.deleteByOwners(QuizOwnerType.LESSON, staleIds);
        completedLessonRepository.deleteByLessonIdIn(staleIds);
        lessonRepository.deleteAll(stale);
        // Flushed here so the module deletes below cannot trip over a lesson still referencing them.
        lessonRepository.flush();
    }

    private void removeStaleModules(List<CourseModule> persisted, Set<Long> retainedIds) {
        List<CourseModule> stale = persisted.stream()
                .filter(module -> !retainedIds.contains(module.getId()))
                .toList();
        if (stale.isEmpty()) {
            return;
        }

        quizService.deleteByOwners(QuizOwnerType.MODULE, stale.stream().map(CourseModule::getId).toList());
        courseModuleRepository.deleteAll(stale);
        courseModuleRepository.flush();
    }

    /**
     * Plans are only meaningful for subscription courses, so any other access type clears them.
     * A subscription course whose payload omits the collection keeps what it has — that is the
     * same "absent means untouched" rule the content tree follows.
     */
    private void syncSubscriptionPlans(Course course, CourseRequest request, ResolvedCourseSettings settings) {
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
            } else {
                plan = resolveOwnChild(plansById, seen, planRequest.getId(),
                        "error.course.planNotInCourse", "error.course.planDuplicate");
                plan.setName(planRequest.getName().trim());
                plan.setDuration(planRequest.getDuration());
                plan.setUnit(planRequest.getUnit());
                plan.setPrice(planRequest.getPrice());
                plan.setOrderIndex(order);
            }
            retained.add(plan.getId());
        }

        List<SubscriptionPlan> stale = persisted.stream()
                .filter(plan -> !retained.contains(plan.getId()))
                .toList();
        if (!stale.isEmpty()) {
            subscriptionPlanRepository.deleteAll(stale);
        }
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
    }

    /**
     * A quiz waiting for its owner's id. Modules and lessons created in this pass only receive one
     * once they are flushed, so the owner is captured as a supplier and read afterwards.
     */
    private record QuizAttachment(QuizOwnerType ownerType, Supplier<Long> ownerId, QuizRequest request) {
    }
}
