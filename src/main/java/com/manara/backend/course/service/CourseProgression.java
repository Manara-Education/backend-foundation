package com.manara.backend.course.service;

import com.manara.backend.course.model.CourseModule;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.service.LearnerQuizState;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A viewer's standing in one course: what they may open, what they have finished, and where the
 * curriculum lets them go next.
 *
 * <p>Produced by {@link CourseProgressionService} and consumed by every response that describes a
 * course to a learner, so the rules that decide a lock exist once and every screen reads the same
 * answer.
 *
 * @param tracksProgress whether completion state applies to this viewer at all — false for course
 *                       discovery and for an instructor previewing their own draft, where "not
 *                       completed" would be a claim about a learner who is not there
 */
public record CourseProgression(
        boolean tracksProgress,
        Set<Long> completedLessonIds,
        Set<Long> accessibleLessonIds,
        Set<Long> unlockedModuleIds,
        Map<Long, LearnerQuizState> quizStates,
        int progress,
        boolean curriculumCompleted,
        boolean courseCompleted,
        Long nextLessonId) {

    /**
     * What someone browsing the catalogue sees: the shape of the course, none of its content. Every
     * lesson is locked and every quiz unavailable, which is what the discovery screen renders.
     */
    public static CourseProgression forVisitor() {
        return new CourseProgression(false, Set.of(), Set.of(), Set.of(), Map.of(), 0, false, false, null);
    }

    /**
     * What the owning instructor sees while previewing: everything open, nothing tracked. Their
     * access comes from ownership, so no curriculum rule applies to them.
     */
    public static CourseProgression forOwner(CourseAggregate aggregate) {
        return new CourseProgression(
                false,
                Set.of(),
                aggregate.lessons().stream().map(Lesson::getId).collect(Collectors.toUnmodifiableSet()),
                aggregate.modules().stream().map(CourseModule::getId).collect(Collectors.toUnmodifiableSet()),
                aggregate.allQuizzes().stream()
                        .collect(Collectors.toUnmodifiableMap(Quiz::getId, quiz -> LearnerQuizState.unlocked())),
                0, false, false, null);
    }

    /**
     * The same standing with every door shut — what a lapsed subscriber sees.
     *
     * <p>Everything the learner earned is kept: which lessons they completed, the percentage they
     * reached, whether they finished the course. What goes is only the right to open anything, so
     * renewing restores their exact position instead of starting them over. This is why expiry is a
     * progression concern and not a deletion.
     */
    public CourseProgression suspended() {
        return new CourseProgression(
                tracksProgress,
                completedLessonIds,
                Set.of(),
                Set.of(),
                quizStates.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().withAvailability(false))),
                progress,
                curriculumCompleted,
                courseCompleted,
                // Nothing is open, so there is nowhere to continue to.
                null);
    }

    /** Whether this viewer may be served the lesson's protected content — its video and its quiz. */
    public boolean isLessonAccessible(Lesson lesson) {
        return accessibleLessonIds.contains(lesson.getId());
    }

    /**
     * @return the lesson's completion state, or {@code null} when completion does not apply to this
     * viewer — the same "no answer" the response has always carried for course discovery
     */
    public Boolean completionOf(Lesson lesson) {
        return tracksProgress ? completedLessonIds.contains(lesson.getId()) : null;
    }

    public boolean isModuleUnlocked(Long moduleId) {
        return unlockedModuleIds.contains(moduleId);
    }

    /** Never null: a quiz outside this progression's course is simply not available. */
    public LearnerQuizState stateOf(Quiz quiz) {
        if (quiz == null) {
            return null;
        }
        return quizStates.getOrDefault(quiz.getId(), LearnerQuizState.locked());
    }
}
