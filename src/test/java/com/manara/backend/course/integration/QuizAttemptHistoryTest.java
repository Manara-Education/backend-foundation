package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.QuizAnswerRequest;
import com.manara.backend.quiz.dto.QuizAttemptResponse;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.dto.QuizSubmissionRequest;
import com.manara.backend.quiz.service.QuizAttemptService;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonWithQuiz;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.moduleWithExam;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A submitted quiz attempt, and everything an instructor can do to the quiz afterwards.
 *
 * <h2>The failure this closes</h2>
 * {@code quiz_attempt_answers} referenced the question and the chosen option with
 * {@code ON DELETE CASCADE}, and {@code quiz_attempts} — a separate table, carrying the score — did
 * not. So deleting the option a learner had picked deleted their answer row and left the attempt
 * reading {@code score=100, correct_count=1, passed=true} with nothing behind it. A result screen
 * with a mark and no questions.
 *
 * <p>An attempt is evidence of what somebody was asked and what they answered on a particular day.
 * The quiz goes on being edited; the evidence must not. The row now survives its question and its
 * option, and carries a copy of what both said at the time — which is also the only correct answer
 * once the question has been reworded, because a rewording describes a quiz this learner never sat.
 *
 * <p>Every assertion reads the database directly. An attempt that is intact in the persistence
 * context and missing on disk is exactly the failure being closed.
 */
class QuizAttemptHistoryTest extends AbstractCourseAuthoringTest {

    @Autowired QuizAttemptService quizAttemptService;

    private User learner;

    @BeforeEach
    void createLearner() {
        learner = newStudentUser();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** A three-option question whose second option is the answer key. */
    private QuizRequest oneQuestionQuiz(String title) {
        return QuizRequest.builder()
                .title(title)
                .instructions(title + " instructions")
                .passingScore(50)
                .questions(List.of(QuizQuestionRequest.builder()
                        .id("q1")
                        .text("Which one is right?")
                        .correctOptionId("b")
                        .options(List.of(
                                QuizOptionRequest.builder().id("a").text("Option A").build(),
                                QuizOptionRequest.builder().id("b").text("Option B").build(),
                                QuizOptionRequest.builder().id("c").text("Option C").build()))
                        .build()))
                .build();
    }

    /** Submits the answer key, so the attempt passes and has something worth preserving. */
    private QuizAttemptResponse answerCorrectly(Long courseId, InstructorQuizResponse quiz) {
        var question = quiz.getQuestions().getFirst();
        String correct = question.getOptions().stream()
                .filter(option -> option.getText().equals("Option B"))
                .findFirst().orElseThrow().getId();

        return quizAttemptService.submit(learner, courseId, Long.valueOf(quiz.getId()),
                QuizSubmissionRequest.builder()
                        .answers(List.of(QuizAnswerRequest.builder()
                                .questionId(question.getId())
                                .optionId(correct)
                                .build()))
                        .build());
    }

    private InstructorCourseResponse asLoadedNow(Long courseId) {
        return courseService.getCourseForEditing(instructorUser, courseId);
    }

    /**
     * Marks every lesson of the course complete for the learner.
     *
     * <p>A module exam and a final exam only open once the curriculum in front of them is done, so
     * a test about exam history has to get the learner there first. Written through the repository
     * because what is being set up is the learner's position in the course, not the completion
     * rules themselves — those have their own tests.
     */
    private void completeEveryLesson(Long courseId) {
        var student = studentProfileOf(learner);
        lessonRepository.findByCourseIdOrderByOrderIndexAsc(courseId).forEach(lesson ->
                completedLessonRepository.save(com.manara.backend.lesson.model.CompletedLesson.builder()
                        .student(student).lesson(lesson).build()));
    }

    /** The stored answer rows of one attempt, straight out of PostgreSQL. */
    private List<Map<String, Object>> storedAnswers(Long attemptId) {
        return jdbcTemplate.queryForList(
                "SELECT question_id, selected_option_id, question_text, selected_option_text, "
                        + "correct_option_text, is_correct FROM quiz_attempt_answers WHERE attempt_id = ?",
                attemptId);
    }

    private Map<String, Object> storedAttempt(Long attemptId) {
        return jdbcTemplate.queryForMap(
                "SELECT score, passed, correct_count, total_questions FROM quiz_attempts WHERE id = ?",
                attemptId);
    }

    /** The option ids of the only question of the only quiz, by their text. */
    private Map<String, String> optionIdsByText(InstructorQuizResponse quiz) {
        return quiz.getQuestions().getFirst().getOptions().stream()
                .collect(java.util.stream.Collectors.toMap(o -> o.getText(), o -> o.getId()));
    }

    // ── A lesson quiz ───────────────────────────────────────────────────────

    private record Sat(InstructorCourseResponse course, Long attemptId) {
    }

    /** A published flat course with a lesson quiz the learner has already submitted and passed. */
    private Sat aLessonQuizAlreadySat() {
        var request = flatCourse("Quizzed", CourseStatus.PUBLISHED,
                lessonWithQuiz("L1", oneQuestionQuiz("Lesson Quiz")));
        var course = courseService.createCourse(instructorUser, request);
        enroll(learner, course.getId());

        var quiz = asLoadedNow(course.getId()).getLessons().getFirst().getQuiz();
        var attempt = answerCorrectly(course.getId(), quiz);
        return new Sat(asLoadedNow(course.getId()), attempt.getAttemptId());
    }

    @Test
    @DisplayName("the attempt records what was asked and chosen, not just which rows they were")
    void anAttemptCarriesItsOwnSnapshot() {
        var sat = aLessonQuizAlreadySat();

        var answers = storedAnswers(sat.attemptId());
        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst()).containsEntry("question_text", "Which one is right?");
        assertThat(answers.getFirst()).containsEntry("selected_option_text", "Option B");
        assertThat(answers.getFirst()).containsEntry("correct_option_text", "Option B");
        assertThat(answers.getFirst()).containsEntry("is_correct", true);
    }

    @Test
    @DisplayName("deleting an option the learner did not choose leaves the attempt alone")
    void deletingAnUnusedDistractorChangesNothing() {
        var sat = aLessonQuizAlreadySat();
        var before = storedAnswers(sat.attemptId());

        var edit = echoOf(sat.course());
        var question = edit.getLessons().getFirst().getQuiz().getQuestions().getFirst();
        var ids = optionIdsByText(sat.course().getLessons().getFirst().getQuiz());
        question.setOptions(question.getOptions().stream()
                .filter(option -> !option.getId().equals(ids.get("Option C")))
                .toList());
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        assertThat(storedAnswers(sat.attemptId())).isEqualTo(before);
    }

    /** The reproduction from the audit, exactly as it was run. */
    @Test
    @DisplayName("deleting the option the learner chose keeps their answer readable")
    void deletingTheChosenOptionKeepsTheAnswer() {
        var sat = aLessonQuizAlreadySat();

        var edit = echoOf(sat.course());
        var question = edit.getLessons().getFirst().getQuiz().getQuestions().getFirst();
        var ids = optionIdsByText(sat.course().getLessons().getFirst().getQuiz());
        // Option B goes, replaced by a new one — and the answer key moves to it.
        question.setOptions(List.of(
                QuizOptionRequest.builder().id(ids.get("Option A")).text("Option A").build(),
                QuizOptionRequest.builder().id(ids.get("Option C")).text("Option C").build(),
                QuizOptionRequest.builder().id("new-d").text("Option D").build()));
        question.setCorrectOptionId("new-d");
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        var answers = storedAnswers(sat.attemptId());
        assertThat(answers).as("the answer row must survive the option it pointed at").hasSize(1);

        var answer = answers.getFirst();
        assertThat(answer.get("selected_option_id")).as("the reference is cleared, not dangling").isNull();
        assertThat(answer).containsEntry("selected_option_text", "Option B");
        assertThat(answer).containsEntry("correct_option_text", "Option B");
        assertThat(answer).containsEntry("is_correct", true);

        // And the two halves of the record still agree with each other.
        assertThat(storedAttempt(sat.attemptId()))
                .containsEntry("score", 100)
                .containsEntry("passed", true)
                .containsEntry("correct_count", 1)
                .containsEntry("total_questions", 1);
    }

    @Test
    @DisplayName("renaming the chosen option does not rewrite what the learner picked")
    void renamingTheChosenOptionDoesNotRewriteHistory() {
        var sat = aLessonQuizAlreadySat();

        var edit = echoOf(sat.course());
        var question = edit.getLessons().getFirst().getQuiz().getQuestions().getFirst();
        var ids = optionIdsByText(sat.course().getLessons().getFirst().getQuiz());
        question.getOptions().stream()
                .filter(option -> option.getId().equals(ids.get("Option B")))
                .forEach(option -> option.setText("Option B, reworded"));
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        assertThat(storedAnswers(sat.attemptId()).getFirst())
                .containsEntry("selected_option_text", "Option B");
    }

    @Test
    @DisplayName("moving the answer key does not turn a past pass into a past failure")
    void movingTheAnswerKeyDoesNotRegradeThePast() {
        var sat = aLessonQuizAlreadySat();

        var edit = echoOf(sat.course());
        var question = edit.getLessons().getFirst().getQuiz().getQuestions().getFirst();
        var ids = optionIdsByText(sat.course().getLessons().getFirst().getQuiz());
        question.setCorrectOptionId(ids.get("Option A"));
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        var answer = storedAnswers(sat.attemptId()).getFirst();
        assertThat(answer).containsEntry("is_correct", true);
        assertThat(answer).containsEntry("correct_option_text", "Option B");
        assertThat(storedAttempt(sat.attemptId())).containsEntry("passed", true);
    }

    @Test
    @DisplayName("rewording the question keeps the wording the learner was actually shown")
    void rewordingTheQuestionKeepsWhatWasAsked() {
        var sat = aLessonQuizAlreadySat();

        var edit = echoOf(sat.course());
        edit.getLessons().getFirst().getQuiz().getQuestions().getFirst()
                .setText("Which one is right, exactly?");
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        assertThat(storedAnswers(sat.attemptId()).getFirst())
                .containsEntry("question_text", "Which one is right?");
    }

    @Test
    @DisplayName("deleting the question keeps the answer row, with its reference cleared")
    void deletingTheQuestionKeepsTheAnswer() {
        var sat = aLessonQuizAlreadySat();

        var edit = echoOf(sat.course());
        var quiz = edit.getLessons().getFirst().getQuiz();
        quiz.setQuestions(List.of(QuizQuestionRequest.builder()
                .id("replacement")
                .text("An entirely different question")
                .correctOptionId("x")
                .options(List.of(
                        QuizOptionRequest.builder().id("x").text("X").build(),
                        QuizOptionRequest.builder().id("y").text("Y").build()))
                .build()));
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        var answers = storedAnswers(sat.attemptId());
        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().get("question_id")).isNull();
        assertThat(answers.getFirst()).containsEntry("question_text", "Which one is right?");
        assertThat(storedAttempt(sat.attemptId())).containsEntry("score", 100);
    }

    @Test
    @DisplayName("a new attempt is graded against the quiz as it stands now")
    void newAttemptsUseTheCurrentDefinition() {
        var sat = aLessonQuizAlreadySat();

        // The key moves to Option A. The learner's old attempt keeps its pass; a new one does not.
        var edit = echoOf(sat.course());
        var ids = optionIdsByText(sat.course().getLessons().getFirst().getQuiz());
        edit.getLessons().getFirst().getQuiz().getQuestions().getFirst()
                .setCorrectOptionId(ids.get("Option A"));
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        var quizNow = asLoadedNow(sat.course().getId()).getLessons().getFirst().getQuiz();
        var second = answerCorrectly(sat.course().getId(), quizNow);

        assertThat(second.getPassed()).isFalse();
        assertThat(storedAttempt(sat.attemptId())).containsEntry("passed", true);
    }

    // ── Module exams and final exams ────────────────────────────────────────

    @Test
    @DisplayName("a module exam's history survives the same editing")
    void aModuleExamKeepsItsHistory() {
        var course = courseService.createCourse(instructorUser,
                modularCourse("Examined", CourseStatus.PUBLISHED,
                        moduleWithExam("One", oneQuestionQuiz("Module Exam"), lesson("L1"))));
        enroll(learner, course.getId());
        completeEveryLesson(course.getId());

        var loaded = asLoadedNow(course.getId());
        var attempt = answerCorrectly(course.getId(), loaded.getModules().getFirst().getQuiz());

        var edit = echoOf(loaded);
        var ids = optionIdsByText(loaded.getModules().getFirst().getQuiz());
        var question = edit.getModules().getFirst().getQuiz().getQuestions().getFirst();
        question.setOptions(question.getOptions().stream()
                .filter(option -> !option.getId().equals(ids.get("Option B")))
                .toList());
        question.setCorrectOptionId(ids.get("Option A"));
        courseService.updateCourse(instructorUser, course.getId(), edit);

        assertThat(storedAnswers(attempt.getAttemptId())).hasSize(1);
        assertThat(storedAnswers(attempt.getAttemptId()).getFirst())
                .containsEntry("selected_option_text", "Option B");
    }

    @Test
    @DisplayName("a final exam's history survives the same editing")
    void aFinalExamKeepsItsHistory() {
        var request = modularCourse("Finalised", CourseStatus.PUBLISHED, module("One", lesson("L1")));
        request.setFinalQuiz(oneQuestionQuiz("Final Exam"));
        var course = courseService.createCourse(instructorUser, request);
        enroll(learner, course.getId());
        completeEveryLesson(course.getId());

        var loaded = asLoadedNow(course.getId());
        var attempt = answerCorrectly(course.getId(), loaded.getFinalQuiz());

        var edit = echoOf(loaded);
        var ids = optionIdsByText(loaded.getFinalQuiz());
        var question = edit.getFinalQuiz().getQuestions().getFirst();
        question.setOptions(question.getOptions().stream()
                .filter(option -> !option.getId().equals(ids.get("Option B")))
                .toList());
        question.setCorrectOptionId(ids.get("Option C"));
        courseService.updateCourse(instructorUser, course.getId(), edit);

        assertThat(storedAnswers(attempt.getAttemptId())).hasSize(1);
        assertThat(storedAnswers(attempt.getAttemptId()).getFirst())
                .containsEntry("selected_option_text", "Option B");
    }

    /**
     * The one deletion that still takes an attempt with it, asserted so it stays deliberate.
     *
     * <p>Removing a lesson removes its quiz, and an attempt at a quiz that no longer exists has
     * nothing left to describe. It is deleted whole rather than left as a score with no questions —
     * which is the same rule the rest of this file enforces, applied to the case where the whole
     * thing is gone. This is the documented destructive authoring operation, not a silent loss.
     */
    @Test
    @DisplayName("deleting the quiz outright removes the attempt whole, header and answers together")
    void deletingTheQuizRemovesTheAttemptWhole() {
        var sat = aLessonQuizAlreadySat();

        var edit = echoOf(sat.course());
        edit.getLessons().getFirst().setQuiz(null);
        courseService.updateCourse(instructorUser, sat.course().getId(), edit);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM quiz_attempts WHERE id = ?", Integer.class, sat.attemptId()))
                .isZero();
        assertThat(storedAnswers(sat.attemptId())).isEmpty();
    }
}
