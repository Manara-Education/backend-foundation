package com.manara.backend.quiz.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseProgressionCalculator;
import com.manara.backend.course.service.CourseViewer;
import com.manara.backend.course.service.LearnerCourseAccess;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.dto.QuizAnswerRequest;
import com.manara.backend.quiz.dto.QuizSubmissionRequest;
import com.manara.backend.quiz.mapper.QuizAttemptMapper;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.model.QuizQuestion;
import com.manara.backend.quiz.repository.QuizAttemptRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Submitting a quiz: what the server decides, and what it refuses to be told.
 *
 * <p>The recurring theme is that the request contributes option ids and nothing else — no score, no
 * verdict, and no ability to reach a quiz the learner has not earned.
 */
@ExtendWith(MockitoExtension.class)
class QuizAttemptServiceTest {

    private static final Long COURSE_ID = 7L;
    private static final Long LESSON_QUIZ_ID = 500L;

    @Mock
    private LearnerCourseAccess learnerCourseAccess;

    @Mock
    private QuizAttemptRepository quizAttemptRepository;

    private QuizAttemptService quizAttemptService;

    private final User user = User.builder().id(1L).role(Role.STUDENT).build();
    private final Student student = Student.builder().id(3L).build();

    @BeforeEach
    void setUp() {
        quizAttemptService = new QuizAttemptService(
                learnerCourseAccess, quizAttemptRepository, new QuizAttemptMapper(), new QuizGrader());
    }

    // --- grading is the server's -------------------------------------------

    @Test
    void gradesTheSubmissionAndReportsTheResultTheScreenRenders() {
        givenEnrolledLearnerWithOpenLessonQuiz();
        givenAttemptsAreSavedWithIds();

        var response = quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID,
                submission(answer(1, 11), answer(2, 20)));

        assertThat(response.getQuizId()).isEqualTo("500");
        assertThat(response.getCorrectCount()).isEqualTo(1);
        assertThat(response.getTotalQuestions()).isEqualTo(2);
        assertThat(response.getScore()).isEqualTo(50);
        assertThat(response.getPassingScore()).isEqualTo(70);
        assertThat(response.getPassed()).isFalse();
        assertThat(response.getAttemptNumber()).isEqualTo(1);
    }

    @Test
    void theResultReviewsEveryQuestionWithItsAnswerAndExplanation() {
        givenEnrolledLearnerWithOpenLessonQuiz();
        givenAttemptsAreSavedWithIds();

        var response = quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID,
                submission(answer(1, 11), answer(2, 20)));

        assertThat(response.getAnswers()).hasSize(2);
        var first = response.getAnswers().getFirst();
        assertThat(first.getQuestionId()).isEqualTo("1");
        assertThat(first.getSelectedOptionId()).isEqualTo("11");
        assertThat(first.getCorrectOptionId()).isEqualTo("11");
        assertThat(first.getCorrect()).isTrue();
        assertThat(first.getExplanation()).isEqualTo("Because of the rule");

        var second = response.getAnswers().get(1);
        assertThat(second.getSelectedOptionId()).isEqualTo("20");
        assertThat(second.getCorrectOptionId()).isEqualTo("21");
        assertThat(second.getCorrect()).isFalse();
    }

    @Test
    void persistsTheAttemptWithTheStudentCourseAndPassMarkItWasGradedAgainst() {
        givenEnrolledLearnerWithOpenLessonQuiz();
        givenAttemptsAreSavedWithIds();

        quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID, submission(answer(1, 11), answer(2, 21)));

        ArgumentCaptor<QuizAttempt> saved = ArgumentCaptor.forClass(QuizAttempt.class);
        verify(quizAttemptRepository).save(saved.capture());

        QuizAttempt attempt = saved.getValue();
        assertThat(attempt.getStudent()).isSameAs(student);
        assertThat(attempt.getCourse().getId()).isEqualTo(COURSE_ID);
        assertThat(attempt.getQuiz().getId()).isEqualTo(LESSON_QUIZ_ID);
        assertThat(attempt.getScore()).isEqualTo(100);
        assertThat(attempt.getPassingScore()).isEqualTo(70);
        assertThat(attempt.getPassed()).isTrue();
        assertThat(attempt.getAnswers()).hasSize(2);
        assertThat(attempt.getAnswers().getFirst().getCorrect()).isTrue();
    }

    @Test
    void numbersEachRetryAfterTheAttemptsAlreadyOnRecord() {
        givenEnrolledLearnerWithOpenLessonQuiz();
        givenAttemptsAreSavedWithIds();
        given(quizAttemptRepository.countByStudentIdAndQuizId(3L, LESSON_QUIZ_ID)).willReturn(2);

        var response = quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID,
                submission(answer(1, 11), answer(2, 21)));

        assertThat(response.getAttemptNumber()).isEqualTo(3);
    }

    // --- what a submission cannot reach --------------------------------------

    @Test
    void refusesAQuizThatBelongsToAnotherCourse() {
        givenEnrolledLearnerWithOpenLessonQuiz();

        assertThatThrownBy(() -> quizAttemptService.submit(user, COURSE_ID, 999L, submission(answer(1, 11))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.notInCourse");

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void refusesAQuizTheCurriculumHasNotOpenedYet() {
        givenLearnerWithLockedLessonQuiz();

        assertThatThrownBy(() -> quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID,
                submission(answer(1, 11), answer(2, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.locked");

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void refusesALearnerWhoIsNotEnrolledBeforeReadingAnyQuizContent() {
        given(learnerCourseAccess.requireEnrolled(user, COURSE_ID))
                .willThrow(new BusinessException("error.course.notEnrolled"));

        assertThatThrownBy(() -> quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID,
                submission(answer(1, 11))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.notEnrolled");

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void refusesAnAnswerThatNamesAnotherQuestionsOption() {
        givenEnrolledLearnerWithOpenLessonQuiz();

        assertThatThrownBy(() -> quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID,
                submission(answer(1, 21), answer(2, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptOptionUnknown");

        verify(quizAttemptRepository, never()).save(any());
    }

    @Test
    void refusesAnEmptyBody() {
        givenEnrolledLearnerWithOpenLessonQuiz();

        assertThatThrownBy(() -> quizAttemptService.submit(user, COURSE_ID, LESSON_QUIZ_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptAnswersRequired");
    }

    // --- fixtures ------------------------------------------------------------

    private void givenEnrolledLearnerWithOpenLessonQuiz() {
        CourseAggregate aggregate = aggregate();
        // One lesson, no modules: the curriculum opens its quiz from the start.
        CourseProgression progression = new CourseProgressionCalculator()
                .compute(aggregate, Set.of(), Map.of());
        given(learnerCourseAccess.requireEnrolled(user, COURSE_ID))
                .willReturn(new CourseViewer(aggregate.course(), student, null, aggregate, progression));
    }

    private void givenLearnerWithLockedLessonQuiz() {
        CourseAggregate aggregate = aggregate();
        given(learnerCourseAccess.requireEnrolled(user, COURSE_ID))
                .willReturn(new CourseViewer(aggregate.course(), student, null, aggregate,
                        CourseProgression.forVisitor()));
    }

    private void givenAttemptsAreSavedWithIds() {
        AtomicLong ids = new AtomicLong(1000);
        given(quizAttemptRepository.save(any(QuizAttempt.class))).willAnswer(invocation -> {
            QuizAttempt attempt = invocation.getArgument(0);
            attempt.setId(ids.incrementAndGet());
            return attempt;
        });
    }

    private CourseAggregate aggregate() {
        Course course = Course.builder().id(COURSE_ID).title("Course").structure(CourseStructure.FLAT).build();
        Lesson lesson = Lesson.builder().id(1L).title("Lesson").course(course).orderIndex(0).build();
        return new CourseAggregate(course, List.of(), List.of(lesson),
                Map.of(1L, lessonQuiz()), Map.of(), null, List.of());
    }

    /** Two questions: 11 and 21 are the correct options. */
    private Quiz lessonQuiz() {
        Quiz quiz = Quiz.builder()
                .id(LESSON_QUIZ_ID)
                .ownerType(QuizOwnerType.LESSON)
                .ownerId(1L)
                .title("Lesson Quiz")
                .passingScore(70)
                .questions(new ArrayList<>())
                .build();
        quiz.addQuestion(question(1L, "Because of the rule"));
        quiz.addQuestion(question(2L, null));
        return quiz;
    }

    private QuizQuestion question(Long id, String explanation) {
        QuizQuestion question = QuizQuestion.builder()
                .id(id)
                .text("Question " + id)
                .explanation(explanation)
                .orderIndex(id.intValue() - 1)
                .options(new ArrayList<>())
                .build();
        question.addOption(QuizOption.builder().id(id * 10).text("Wrong").correct(false).orderIndex(0).build());
        question.addOption(QuizOption.builder().id(id * 10 + 1).text("Right").correct(true).orderIndex(1).build());
        return question;
    }

    private QuizSubmissionRequest submission(QuizAnswerRequest... answers) {
        return QuizSubmissionRequest.builder().answers(List.of(answers)).build();
    }

    private QuizAnswerRequest answer(long questionId, long optionId) {
        return QuizAnswerRequest.builder()
                .questionId(String.valueOf(questionId))
                .optionId(String.valueOf(optionId))
                .build();
    }
}
