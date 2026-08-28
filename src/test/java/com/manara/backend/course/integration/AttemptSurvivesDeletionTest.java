package com.manara.backend.course.integration;

import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.QuizAnswerRequest;
import com.manara.backend.quiz.dto.QuizSubmissionRequest;
import com.manara.backend.quiz.service.QuizAttemptService;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonWithQuiz;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.quiz;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens to a learner's submitted attempt when the instructor deletes the thing they sat.
 *
 * <p>An attempt is evidence: this person, on this day, was asked these questions and answered
 * these. {@code V9} already made each answer row carry its own snapshot of what was asked and
 * chosen, so an attempt no longer depends on the quiz's current wording to be readable. What it
 * still depended on was the quiz continuing to <em>exist</em>: {@code quiz_attempts.quiz_id} was
 * {@code ON DELETE CASCADE}, so removing a quiz — or the lesson holding it, or the module holding
 * that — silently deleted every attempt anyone had ever made at it.
 *
 * <p>Deleting content an instructor no longer teaches is legitimate and stays legitimate. Deleting
 * the record that somebody sat an exam is not the same act, and the two were welded together.
 */
class AttemptSurvivesDeletionTest extends AbstractCourseAuthoringTest {

    @Autowired QuizAttemptService quizAttemptService;

    private User learner;

    @BeforeEach
    void createLearner() {
        learner = newStudentUser();
    }

    private record Sat(InstructorCourseResponse course, Long attemptId, Long quizId) {
    }

    /** A published course whose second lesson carries a quiz the learner has already submitted. */
    private Sat aLearnerWhoHasSatTheQuiz() {
        CourseRequest create = flatCourse("Assessed course", CourseStatus.PUBLISHED,
                lesson("Lesson one"),
                lessonWithQuiz("Lesson two", quiz("Lesson two quiz")),
                lesson("Lesson three"));
        InstructorCourseResponse course = courseService.createCourse(instructorUser, create);
        enroll(learner, course.getId());

        var student = studentProfileOf(learner);
        lessonRepository.findCourseLessonsInReadingOrder(course.getId()).forEach(l ->
                completedLessonRepository.save(
                        CompletedLesson.builder().student(student).lesson(l).build()));

        InstructorQuizResponse quiz = courseService.getCourseForEditing(instructorUser, course.getId())
                .getLessons().get(1).getQuiz();

        List<QuizAnswerRequest> answers = quiz.getQuestions().stream()
                .map(question -> QuizAnswerRequest.builder()
                        .questionId(question.getId())
                        .optionId(question.getCorrectOptionId())
                        .build())
                .toList();

        Long attemptId = quizAttemptService.submit(learner, course.getId(), Long.valueOf(quiz.getId()),
                QuizSubmissionRequest.builder().answers(answers).build()).getAttemptId();

        return new Sat(courseService.getCourseForEditing(instructorUser, course.getId()),
                attemptId, Long.valueOf(quiz.getId()));
    }

    private Map<String, Object> storedAttempt(Long attemptId) {
        return jdbcTemplate.queryForMap(
                "SELECT quiz_id, quiz_title, score, passed, correct_count, total_questions "
                        + "FROM quiz_attempts WHERE id = ?", attemptId);
    }

    private List<Map<String, Object>> storedAnswers(Long attemptId) {
        return jdbcTemplate.queryForList(
                "SELECT question_text, selected_option_text, is_correct "
                        + "FROM quiz_attempt_answers WHERE attempt_id = ?", attemptId);
    }

    @Nested
    @DisplayName("deleting the quiz")
    class DeletingTheQuiz {

        @Test
        @DisplayName("keeps the attempt, its score and every answer it recorded")
        void theAttemptOutlivesTheQuiz() {
            Sat sat = aLearnerWhoHasSatTheQuiz();
            var answersBefore = storedAnswers(sat.attemptId());
            var attemptBefore = storedAttempt(sat.attemptId());

            CourseRequest save = echoOf(sat.course());
            save.getLessons().get(1).setQuiz(null);
            courseService.updateCourse(instructorUser, sat.course().getId(), save);

            var after = storedAttempt(sat.attemptId());
            assertThat(after.get("score")).isEqualTo(attemptBefore.get("score"));
            assertThat(after.get("passed")).isEqualTo(attemptBefore.get("passed"));
            assertThat(after.get("total_questions")).isEqualTo(attemptBefore.get("total_questions"));
            assertThat(storedAnswers(sat.attemptId())).isEqualTo(answersBefore);
        }

        @Test
        @DisplayName("detaches it from the quiz but keeps the quiz's title on the record")
        void theAttemptStillSaysWhatItWas() {
            Sat sat = aLearnerWhoHasSatTheQuiz();

            CourseRequest save = echoOf(sat.course());
            save.getLessons().get(1).setQuiz(null);
            courseService.updateCourse(instructorUser, sat.course().getId(), save);

            var after = storedAttempt(sat.attemptId());
            assertThat(after.get("quiz_id")).isNull();
            assertThat(after.get("quiz_title")).isEqualTo("Lesson two quiz");
        }
    }

    @Nested
    @DisplayName("deleting the lesson that carries the quiz")
    class DeletingTheLesson {

        @Test
        @DisplayName("keeps the attempt whole, even though the lesson and quiz are both gone")
        void theAttemptOutlivesItsLesson() {
            Sat sat = aLearnerWhoHasSatTheQuiz();
            var answersBefore = storedAnswers(sat.attemptId());

            CourseRequest save = echoOf(sat.course());
            var lessons = new ArrayList<>(save.getLessons());
            lessons.removeIf(l -> "Lesson two".equals(l.getTitle()));
            save.setLessons(lessons);
            courseService.updateCourse(instructorUser, sat.course().getId(), save);

            assertThat(jdbcTemplate.queryForList(
                    "SELECT id FROM quizzes WHERE id = ?", sat.quizId())).isEmpty();
            assertThat(storedAnswers(sat.attemptId())).isEqualTo(answersBefore);
            assertThat(storedAttempt(sat.attemptId()).get("quiz_title")).isEqualTo("Lesson two quiz");
        }

        @Test
        @DisplayName("the learner's course view still loads, with the detached attempt behind it")
        void theCourseStillRendersForTheLearner() {
            Sat sat = aLearnerWhoHasSatTheQuiz();

            CourseRequest save = echoOf(sat.course());
            var lessons = new ArrayList<>(save.getLessons());
            lessons.removeIf(l -> "Lesson two".equals(l.getTitle()));
            save.setLessons(lessons);
            courseService.updateCourse(instructorUser, sat.course().getId(), save);

            var details = detailsFor(learner, sat.course().getId());
            assertThat(details.getLessons()).extracting(l -> l.getTitle())
                    .containsExactly("Lesson one", "Lesson three");
        }
    }

    @Nested
    @DisplayName("an ordinary quiz edit")
    class OrdinaryEdits {

        @Test
        @DisplayName("still leaves the attempt attached to the quiz it was sat against")
        void editingAQuizDoesNotDetachAnything() {
            Sat sat = aLearnerWhoHasSatTheQuiz();

            CourseRequest save = echoOf(sat.course());
            save.getLessons().get(1).getQuiz().setTitle("Lesson two quiz, revised");
            courseService.updateCourse(instructorUser, sat.course().getId(), save);

            assertThat(storedAttempt(sat.attemptId()).get("quiz_id"))
                    .isEqualTo(sat.quizId());
        }
    }
}
