package com.manara.backend.quiz.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.quiz.dto.QuizAnswerRequest;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizQuestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The scoring rules, and the reasons a submission is refused rather than scored.
 *
 * <p>The distinction matters: a wrong answer is a result, but a question or option that does not
 * belong to the quiz is a malformed request. Treating the second as the first would let a client
 * probe another quiz's ids one submission at a time.
 */
class QuizGraderTest {

    private final QuizGrader grader = new QuizGrader();

    // --- scoring -------------------------------------------------------------

    @Test
    void scoresAFullyCorrectSubmissionAsAHundred() {
        Quiz quiz = quiz(70, 4);

        var graded = grader.grade(quiz, List.of(
                answer(1, 11), answer(2, 21), answer(3, 31), answer(4, 41)));

        assertThat(graded.correctCount()).isEqualTo(4);
        assertThat(graded.totalQuestions()).isEqualTo(4);
        assertThat(graded.score()).isEqualTo(100);
        assertThat(graded.passed()).isTrue();
    }

    @Test
    void scoresEveryWrongAnswerAsZero() {
        Quiz quiz = quiz(70, 2);

        var graded = grader.grade(quiz, List.of(answer(1, 10), answer(2, 20)));

        assertThat(graded.correctCount()).isZero();
        assertThat(graded.score()).isZero();
        assertThat(graded.passed()).isFalse();
    }

    @Test
    void roundsTheScoreTheWayTheResultScreenDoes() {
        // 2 of 3 is 66.67, which both the player and the server report as 67.
        var graded = grader.grade(quiz(70, 3), List.of(answer(1, 11), answer(2, 21), answer(3, 30)));

        assertThat(graded.score()).isEqualTo(67);
    }

    // --- the pass mark -------------------------------------------------------

    @Test
    void passesWhenTheScoreExactlyMeetsThePassMark() {
        var graded = grader.grade(quiz(50, 2), List.of(answer(1, 11), answer(2, 20)));

        assertThat(graded.score()).isEqualTo(50);
        assertThat(graded.passingScore()).isEqualTo(50);
        assertThat(graded.passed()).isTrue();
    }

    @Test
    void failsOneMarkBelowThePassMark() {
        var graded = grader.grade(quiz(70, 4), List.of(
                answer(1, 11), answer(2, 21), answer(3, 30), answer(4, 40)));

        assertThat(graded.score()).isEqualTo(50);
        assertThat(graded.passed()).isFalse();
    }

    @Test
    void measuresAgainstTheQuizzesOwnPassMarkRatherThanAFixedOne() {
        var lenient = grader.grade(quiz(40, 2), List.of(answer(1, 11), answer(2, 20)));
        var strict = grader.grade(quiz(90, 2), List.of(answer(1, 11), answer(2, 20)));

        assertThat(lenient.passed()).isTrue();
        assertThat(strict.passed()).isFalse();
    }

    // --- what is refused rather than scored ----------------------------------

    @Test
    void refusesAQuestionThatBelongsToAnotherQuiz() {
        assertThatThrownBy(() -> grader.grade(quiz(70, 2), List.of(answer(1, 11), answer(99, 11))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptQuestionUnknown");
    }

    @Test
    void refusesAnOptionThatBelongsToAnotherQuestionOfTheSameQuiz() {
        // 21 is the correct option — of question 2. Offered for question 1 it must not count.
        assertThatThrownBy(() -> grader.grade(quiz(70, 2), List.of(answer(1, 21), answer(2, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptOptionUnknown");
    }

    @Test
    void refusesAnOptionIdThatIsNotANumber() {
        assertThatThrownBy(() -> grader.grade(quiz(70, 1), List.of(
                QuizAnswerRequest.builder().questionId("1").optionId("' or 1=1").build())))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptOptionUnknown");
    }

    @Test
    void refusesTheSameQuestionAnsweredTwice() {
        assertThatThrownBy(() -> grader.grade(quiz(70, 2), List.of(
                answer(1, 11), answer(1, 10), answer(2, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptQuestionDuplicate");
    }

    @Test
    void refusesASubmissionThatLeavesAQuestionUnanswered() {
        assertThatThrownBy(() -> grader.grade(quiz(70, 3), List.of(answer(1, 11), answer(2, 21))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptQuestionUnanswered");
    }

    @Test
    void refusesAnEmptySubmission() {
        assertThatThrownBy(() -> grader.grade(quiz(70, 2), List.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptAnswersRequired");

        assertThatThrownBy(() -> grader.grade(quiz(70, 2), null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.attemptAnswersRequired");
    }

    // --- fixtures ------------------------------------------------------------

    /**
     * A quiz of {@code questionCount} questions. Question {@code n} has id {@code n} and two
     * options, {@code n0} (wrong) and {@code n1} (correct).
     */
    private Quiz quiz(int passingScore, int questionCount) {
        Quiz quiz = Quiz.builder()
                .id(500L)
                .title("Quiz")
                .passingScore(passingScore)
                .questions(new ArrayList<>())
                .build();

        for (int n = 1; n <= questionCount; n++) {
            QuizQuestion question = QuizQuestion.builder()
                    .id((long) n)
                    .text("Question " + n)
                    .orderIndex(n - 1)
                    .options(new ArrayList<>())
                    .build();
            question.addOption(option(n * 10L, false));
            question.addOption(option(n * 10L + 1, true));
            quiz.addQuestion(question);
        }
        return quiz;
    }

    private QuizOption option(long id, boolean correct) {
        return QuizOption.builder().id(id).text("Option " + id).correct(correct).orderIndex(0).build();
    }

    private QuizAnswerRequest answer(long questionId, long optionId) {
        return QuizAnswerRequest.builder()
                .questionId(String.valueOf(questionId))
                .optionId(String.valueOf(optionId))
                .build();
    }
}
