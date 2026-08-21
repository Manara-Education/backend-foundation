package com.manara.backend.course.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.model.QuizOwnerType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The curriculum rules, stated as the learner experiences them.
 *
 * <p>These are the rules the prototype held in component state and the frontend would otherwise
 * have to reimplement. Asserting them here is what makes the backend the authority: a client that
 * disagrees is simply wrong.
 */
class CourseProgressionCalculatorTest {

    private static final Map<Long, List<QuizAttempt>> NO_ATTEMPTS = Map.of();

    private final CourseProgressionCalculator calculator = new CourseProgressionCalculator();

    // --- flat courses --------------------------------------------------------

    @Test
    void aFlatCourseOpensEveryLessonAtOnce() {
        var aggregate = flatCourse(3);

        var progression = calculator.compute(aggregate, Set.of(), NO_ATTEMPTS);

        assertThat(progression.accessibleLessonIds()).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(progression.progress()).isZero();
        assertThat(progression.nextLessonId()).isEqualTo(1L);
    }

    @Test
    void progressCountsCompletedLessonsAndRoundsTheWayTheCourseCardDoes() {
        var aggregate = flatCourse(3);

        assertThat(calculator.compute(aggregate, Set.of(1L), NO_ATTEMPTS).progress()).isEqualTo(33);
        assertThat(calculator.compute(aggregate, Set.of(1L, 2L), NO_ATTEMPTS).progress()).isEqualTo(67);
        assertThat(calculator.compute(aggregate, Set.of(1L, 2L, 3L), NO_ATTEMPTS).progress()).isEqualTo(100);
    }

    @Test
    void theNextLessonIsTheFirstOneStillUnfinished() {
        var progression = calculator.compute(flatCourse(3), Set.of(1L), NO_ATTEMPTS);

        assertThat(progression.nextLessonId()).isEqualTo(2L);
    }

    @Test
    void thereIsNoNextLessonOnceTheyAreAllDone() {
        var progression = calculator.compute(flatCourse(2), Set.of(1L, 2L), NO_ATTEMPTS);

        assertThat(progression.nextLessonId()).isNull();
    }

    @Test
    void anEmptyCourseIsNotAFinishedCourse() {
        var progression = calculator.compute(flatCourse(0), Set.of(), NO_ATTEMPTS);

        assertThat(progression.curriculumCompleted()).isFalse();
        assertThat(progression.courseCompleted()).isFalse();
        assertThat(progression.progress()).isZero();
    }

    // --- the final exam ------------------------------------------------------

    @Test
    void withoutAFinalExamFinishingTheLessonsFinishesTheCourse() {
        var progression = calculator.compute(flatCourse(2), Set.of(1L, 2L), NO_ATTEMPTS);

        assertThat(progression.curriculumCompleted()).isTrue();
        assertThat(progression.courseCompleted()).isTrue();
    }

    @Test
    void theFinalExamStaysLockedUntilTheCurriculumIsFinished() {
        var aggregate = flatCourseWithFinalExam(2);

        var partway = calculator.compute(aggregate, Set.of(1L), NO_ATTEMPTS);

        assertThat(partway.stateOf(aggregate.finalQuiz()).available()).isFalse();
        assertThat(partway.courseCompleted()).isFalse();
    }

    @Test
    void theFinalExamUnlocksOnTheLastLessonButDoesNotCompleteTheCourseByItself() {
        var aggregate = flatCourseWithFinalExam(2);

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), NO_ATTEMPTS);

        assertThat(progression.stateOf(aggregate.finalQuiz()).available()).isTrue();
        assertThat(progression.curriculumCompleted()).isTrue();
        assertThat(progression.courseCompleted()).isFalse();
    }

    @Test
    void passingTheFinalExamCompletesTheCourse() {
        var aggregate = flatCourseWithFinalExam(2);

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), attempts(900L, failed(), passed()));

        assertThat(progression.courseCompleted()).isTrue();
        assertThat(progression.stateOf(aggregate.finalQuiz()).passed()).isTrue();
    }

    @Test
    void failingTheFinalExamLeavesTheCourseUnfinished() {
        var aggregate = flatCourseWithFinalExam(2);

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), attempts(900L, failed(), failed()));

        assertThat(progression.courseCompleted()).isFalse();
        assertThat(progression.stateOf(aggregate.finalQuiz()).attemptCount()).isEqualTo(2);
    }

    // --- module progression --------------------------------------------------

    @Test
    void onlyTheFirstModuleIsOpenToALearnerWhoHasJustStarted() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(), NO_ATTEMPTS);

        assertThat(progression.isModuleUnlocked(10L)).isTrue();
        assertThat(progression.isModuleUnlocked(20L)).isFalse();
        assertThat(progression.accessibleLessonIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void aModuleExamStaysLockedUntilItsOwnLessonsAreComplete() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(1L), NO_ATTEMPTS);

        assertThat(progression.stateOf(quiz(aggregate, 10L)).available()).isFalse();
    }

    @Test
    void aModuleExamUnlocksOnTheLastLessonOfItsModule() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), NO_ATTEMPTS);

        assertThat(progression.stateOf(quiz(aggregate, 10L)).available()).isTrue();
    }

    @Test
    void finishingAModulesLessonsIsNotEnoughToOpenTheNextModule() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), NO_ATTEMPTS);

        assertThat(progression.isModuleUnlocked(20L)).isFalse();
        assertThat(progression.accessibleLessonIds()).doesNotContain(3L, 4L);
    }

    @Test
    void passingTheModuleExamOpensTheNextModule() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), attempts(100L, passed()));

        assertThat(progression.isModuleUnlocked(20L)).isTrue();
        assertThat(progression.accessibleLessonIds()).contains(3L, 4L);
        assertThat(progression.nextLessonId()).isEqualTo(3L);
    }

    @Test
    void failingTheModuleExamKeepsTheNextModuleShut() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), attempts(100L, failed(), failed()));

        assertThat(progression.isModuleUnlocked(20L)).isFalse();
        assertThat(progression.stateOf(quiz(aggregate, 10L)).attemptCount()).isEqualTo(2);
        assertThat(progression.stateOf(quiz(aggregate, 10L)).passed()).isFalse();
    }

    @Test
    void aModuleWithoutAnExamIsFinishedByItsLessonsAlone() {
        var aggregate = twoModulesFirstWithoutExam();

        var progression = calculator.compute(aggregate, Set.of(1L, 2L), NO_ATTEMPTS);

        assertThat(progression.isModuleUnlocked(20L)).isTrue();
    }

    @Test
    void aModularCourseIsFinishedOnlyWhenEveryModuleIs() {
        var aggregate = twoModulesEachWithAnExam();

        var throughFirst = calculator.compute(aggregate, Set.of(1L, 2L), attempts(100L, passed()));
        assertThat(throughFirst.curriculumCompleted()).isFalse();

        Map<Long, List<QuizAttempt>> bothPassed = new HashMap<>(attempts(100L, passed()));
        bothPassed.putAll(attempts(200L, passed()));
        var throughBoth = calculator.compute(aggregate, Set.of(1L, 2L, 3L, 4L), bothPassed);

        assertThat(throughBoth.curriculumCompleted()).isTrue();
        assertThat(throughBoth.courseCompleted()).isTrue();
        assertThat(throughBoth.progress()).isEqualTo(100);
    }

    @Test
    void aLessonQuizInsideALockedModuleIsLockedWithIt() {
        var aggregate = twoModulesEachWithAnExam();

        var progression = calculator.compute(aggregate, Set.of(), NO_ATTEMPTS);

        assertThat(progression.stateOf(quiz(aggregate, 3L)).available()).isFalse();
        assertThat(progression.stateOf(quiz(aggregate, 1L)).available()).isTrue();
    }

    // --- attempt history -----------------------------------------------------

    @Test
    void theQuizStateReportsTheBestScoreAndTheMostRecentAttempt() {
        var aggregate = flatCourseWithFinalExam(1);

        var progression = calculator.compute(aggregate, Set.of(1L),
                attempts(900L, attempt(1L, 40, false), attempt(2L, 90, true), attempt(3L, 60, false)));

        var state = progression.stateOf(aggregate.finalQuiz());
        assertThat(state.attemptCount()).isEqualTo(3);
        assertThat(state.bestScore()).isEqualTo(90);
        assertThat(state.passed()).isTrue();
        assertThat(state.lastAttemptId()).isEqualTo(3L);
    }

    @Test
    void aQuizNeverAttemptedReportsNoScoreAtAll() {
        var aggregate = flatCourseWithFinalExam(1);

        var state = calculator.compute(aggregate, Set.of(1L), NO_ATTEMPTS).stateOf(aggregate.finalQuiz());

        assertThat(state.attemptCount()).isZero();
        assertThat(state.bestScore()).isNull();
        assertThat(state.passed()).isFalse();
    }

    // --- fixtures ------------------------------------------------------------

    private CourseAggregate flatCourse(int lessonCount) {
        Course course = course(CourseStructure.FLAT);
        List<Lesson> lessons = new ArrayList<>();
        Map<Long, Quiz> lessonQuizzes = new LinkedHashMap<>();
        for (int i = 1; i <= lessonCount; i++) {
            lessons.add(lesson(i, course, null));
        }
        return new CourseAggregate(course, List.of(), lessons, lessonQuizzes, Map.of(), null, List.of());
    }

    private CourseAggregate flatCourseWithFinalExam(int lessonCount) {
        CourseAggregate flat = flatCourse(lessonCount);
        return new CourseAggregate(flat.course(), List.of(), flat.lessons(), flat.lessonQuizzes(), Map.of(),
                quiz(900L, QuizOwnerType.COURSE, flat.course().getId()), List.of());
    }

    /** Two modules of two lessons each. Lesson 1 and lesson 3 also carry a lesson quiz. */
    private CourseAggregate twoModulesEachWithAnExam() {
        return modularCourse(true);
    }

    private CourseAggregate twoModulesFirstWithoutExam() {
        return modularCourse(false);
    }

    private CourseAggregate modularCourse(boolean firstModuleHasExam) {
        Course course = course(CourseStructure.MODULES);
        CourseModule first = module(10L, course, 0);
        CourseModule second = module(20L, course, 1);

        List<Lesson> lessons = List.of(
                lesson(1, course, first), lesson(2, course, first),
                lesson(3, course, second), lesson(4, course, second));

        Map<Long, Quiz> lessonQuizzes = new LinkedHashMap<>();
        lessonQuizzes.put(1L, quiz(1L, QuizOwnerType.LESSON, 1L));
        lessonQuizzes.put(3L, quiz(3L, QuizOwnerType.LESSON, 3L));

        Map<Long, Quiz> moduleQuizzes = new LinkedHashMap<>();
        if (firstModuleHasExam) {
            moduleQuizzes.put(10L, quiz(100L, QuizOwnerType.MODULE, 10L));
        }
        moduleQuizzes.put(20L, quiz(200L, QuizOwnerType.MODULE, 20L));

        return new CourseAggregate(course, List.of(first, second), lessons, lessonQuizzes, moduleQuizzes, null, List.of());
    }

    private Course course(CourseStructure structure) {
        return Course.builder().id(7L).title("Course").structure(structure).build();
    }

    private CourseModule module(Long id, Course course, int orderIndex) {
        return CourseModule.builder().id(id).course(course).title("Module " + id).orderIndex(orderIndex).build();
    }

    private Lesson lesson(int id, Course course, CourseModule module) {
        return Lesson.builder()
                .id((long) id)
                .title("Lesson " + id)
                .course(course)
                .module(module)
                .orderIndex(id)
                .build();
    }

    private Quiz quiz(Long id, QuizOwnerType ownerType, Long ownerId) {
        return Quiz.builder()
                .id(id)
                .ownerType(ownerType)
                .ownerId(ownerId)
                .title("Quiz " + id)
                .passingScore(70)
                .questions(new ArrayList<>())
                .build();
    }

    /** The quiz owned by a lesson or module of the aggregate, whichever holds this owner id. */
    private Quiz quiz(CourseAggregate aggregate, Long ownerId) {
        Quiz lessonQuiz = aggregate.lessonQuizzes().get(ownerId);
        return lessonQuiz != null ? lessonQuiz : aggregate.moduleQuizzes().get(ownerId);
    }

    private Map<Long, List<QuizAttempt>> attempts(Long quizId, QuizAttempt... attempts) {
        return Map.of(quizId, List.of(attempts));
    }

    private QuizAttempt passed() {
        return attempt(1L, 100, true);
    }

    private QuizAttempt failed() {
        return attempt(1L, 20, false);
    }

    private QuizAttempt attempt(Long id, int score, boolean passed) {
        return QuizAttempt.builder()
                .id(id)
                .score(score)
                .passed(passed)
                .passingScore(70)
                .submittedAt(LocalDateTime.now())
                .build();
    }
}
