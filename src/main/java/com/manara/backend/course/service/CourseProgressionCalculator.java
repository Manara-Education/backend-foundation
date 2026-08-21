package com.manara.backend.course.service;

import com.manara.backend.course.model.CourseModule;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.service.LearnerQuizState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The curriculum rules themselves — pure, and the one place any of them is written down.
 *
 * <p>Every gate in the product is decided here — whether a module has opened, whether its exam is
 * takeable yet, whether the final exam has unlocked, whether the course is finished. Lesson
 * completion, quiz submission and both course responses all read the resulting
 * {@link CourseProgression}, so a rule cannot be enforced in one place and forgotten in another,
 * and the client never has to re-derive one.
 *
 * <p>Nothing here reads the database: {@link CourseProgressionService} gathers the inputs and this
 * class turns them into an answer, which is what makes the rules testable without a fixture.
 *
 * <p>The rules:
 *
 * <ul>
 *   <li>A flat course opens all of its lessons at once — it has no chapters to sequence.</li>
 *   <li>The first module of a modular course is open; each later one opens when the one before it
 *       is finished.</li>
 *   <li>A module exam becomes takeable once every lesson of its module is complete.</li>
 *   <li>A module is finished when its lessons are complete and its exam, if it has one, is
 *       passed.</li>
 *   <li>The final exam becomes takeable once the whole curriculum is finished.</li>
 *   <li>The course is complete when the curriculum is finished and the final exam, if there is
 *       one, is passed.</li>
 * </ul>
 *
 * <p>Progress stays what it has always been — completed lessons over total lessons. Exams gate
 * what opens next; they do not move the bar the learner watches fill up.
 */
@Component
public class CourseProgressionCalculator {

    /**
     * Applies the progression rules to an already-loaded picture of a learner's state.
     *
     * <p>Kept free of queries so that a caller which has just changed something — marking a lesson
     * complete, recording a passing attempt — can ask for the new state by handing in the updated
     * inputs instead of reading everything back.
     */
    public CourseProgression compute(CourseAggregate aggregate,
                                     Set<Long> completedLessonIds,
                                     Map<Long, List<QuizAttempt>> attemptsByQuiz) {

        Map<Long, LearnerQuizState> quizStates = new LinkedHashMap<>();
        Set<Long> unlockedModuleIds = new HashSet<>();
        Set<Long> accessibleLessonIds = new HashSet<>();

        boolean curriculumCompleted = aggregate.modules().isEmpty()
                ? computeFlat(aggregate, completedLessonIds, attemptsByQuiz, quizStates, accessibleLessonIds)
                : computeModular(aggregate, completedLessonIds, attemptsByQuiz, quizStates,
                        unlockedModuleIds, accessibleLessonIds);

        Quiz finalQuiz = aggregate.finalQuiz();
        boolean courseCompleted = curriculumCompleted;
        if (finalQuiz != null) {
            LearnerQuizState state = stateOf(finalQuiz, curriculumCompleted, attemptsByQuiz);
            quizStates.put(finalQuiz.getId(), state);
            courseCompleted = curriculumCompleted && state.passed();
        }

        return new CourseProgression(
                true,
                Set.copyOf(completedLessonIds),
                Set.copyOf(accessibleLessonIds),
                Set.copyOf(unlockedModuleIds),
                Map.copyOf(quizStates),
                progressPercent(aggregate.lessons(), completedLessonIds),
                curriculumCompleted,
                courseCompleted,
                nextLessonId(aggregate.lessons(), completedLessonIds, accessibleLessonIds));
    }

    /**
     * A flat course has nothing to sequence: every lesson is open from the start, and the
     * curriculum is finished once they are all complete.
     */
    private boolean computeFlat(CourseAggregate aggregate,
                                Set<Long> completedLessonIds,
                                Map<Long, List<QuizAttempt>> attemptsByQuiz,
                                Map<Long, LearnerQuizState> quizStates,
                                Set<Long> accessibleLessonIds) {

        for (Lesson lesson : aggregate.lessons()) {
            accessibleLessonIds.add(lesson.getId());
            putLessonQuizState(aggregate, lesson, true, attemptsByQuiz, quizStates);
        }
        // A course with nothing in it is not a finished course, however you look at it.
        return !aggregate.lessons().isEmpty() && allCompleted(aggregate.lessons(), completedLessonIds);
    }

    /**
     * Modules run in order. Each is opened by the one before it being finished, which is what makes
     * a module exam a gate rather than a formality: skipping it leaves the next module shut.
     */
    private boolean computeModular(CourseAggregate aggregate,
                                   Set<Long> completedLessonIds,
                                   Map<Long, List<QuizAttempt>> attemptsByQuiz,
                                   Map<Long, LearnerQuizState> quizStates,
                                   Set<Long> unlockedModuleIds,
                                   Set<Long> accessibleLessonIds) {

        boolean previousModuleFinished = true;
        boolean everyModuleFinished = true;

        for (CourseModule module : aggregate.modules()) {
            boolean unlocked = previousModuleFinished;
            List<Lesson> moduleLessons = aggregate.lessonsOf(module);

            if (unlocked) {
                unlockedModuleIds.add(module.getId());
                moduleLessons.forEach(lesson -> accessibleLessonIds.add(lesson.getId()));
            }
            for (Lesson lesson : moduleLessons) {
                putLessonQuizState(aggregate, lesson, unlocked, attemptsByQuiz, quizStates);
            }

            boolean lessonsDone = unlocked && allCompleted(moduleLessons, completedLessonIds);

            Quiz exam = aggregate.quizOfModule(module);
            boolean examPassed = true;
            if (exam != null) {
                // The exam opens on the module's lessons being complete, not on the module being
                // finished — it is part of finishing it.
                LearnerQuizState state = stateOf(exam, lessonsDone, attemptsByQuiz);
                quizStates.put(exam.getId(), state);
                examPassed = state.passed();
            }

            boolean moduleFinished = lessonsDone && examPassed;
            everyModuleFinished &= moduleFinished;
            previousModuleFinished = moduleFinished;
        }

        return everyModuleFinished;
    }

    private void putLessonQuizState(CourseAggregate aggregate,
                                    Lesson lesson,
                                    boolean lessonAccessible,
                                    Map<Long, List<QuizAttempt>> attemptsByQuiz,
                                    Map<Long, LearnerQuizState> quizStates) {
        Quiz quiz = aggregate.quizOfLesson(lesson);
        if (quiz != null) {
            quizStates.put(quiz.getId(), stateOf(quiz, lessonAccessible, attemptsByQuiz));
        }
    }

    private LearnerQuizState stateOf(Quiz quiz, boolean available, Map<Long, List<QuizAttempt>> attemptsByQuiz) {
        return LearnerQuizState.of(available, attemptsByQuiz.get(quiz.getId()));
    }

    /**
     * Vacuously true for an empty module: an instructor leaving a chapter empty is an authoring
     * gap, and blocking every learner behind it would turn that into an outage.
     */
    private boolean allCompleted(List<Lesson> lessons, Set<Long> completedLessonIds) {
        return lessons.stream().allMatch(lesson -> completedLessonIds.contains(lesson.getId()));
    }

    /**
     * Lessons only, matching the figure the learner already sees on the course card. Rounded rather
     * than truncated so the last lesson of a course reads 100%.
     */
    private int progressPercent(List<Lesson> lessons, Set<Long> completedLessonIds) {
        if (lessons.isEmpty()) {
            return 0;
        }
        long completed = lessons.stream().filter(lesson -> completedLessonIds.contains(lesson.getId())).count();
        return (int) Math.round(completed * 100.0 / lessons.size());
    }

    /** The first lesson in reading order the learner can open and has not finished. */
    private Long nextLessonId(List<Lesson> lessons, Set<Long> completedLessonIds, Set<Long> accessibleLessonIds) {
        return lessons.stream()
                .filter(lesson -> accessibleLessonIds.contains(lesson.getId()))
                .filter(lesson -> !completedLessonIds.contains(lesson.getId()))
                .map(Lesson::getId)
                .findFirst()
                .orElse(null);
    }
}
