package com.manara.backend.quiz.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * These rules are the whole reason there is a single quiz validator: a lesson quiz, a module exam
 * and a course final exam all arrive here, so proving them once proves them everywhere.
 */
class QuizValidatorTest {

    private final QuizValidator validator = new QuizValidator();

    @Test
    void acceptsAWellFormedQuiz() {
        assertThatCode(() -> validator.validate(quiz(question("q1", "b",
                option("a", "Answer A"),
                option("b", "Answer B")))))
                .doesNotThrowAnyException();
    }

    @Test
    void treatsAnAbsentQuizAsNothingToValidate() {
        assertThatCode(() -> validator.validateIfPresent(null)).doesNotThrowAnyException();
    }

    @Test
    void rejectsABlankTitle() {
        QuizRequest request = quiz(question("q1", "a", option("a", "A"), option("b", "B")));
        request.setTitle("  ");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.titleRequired");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 101, 1000})
    void rejectsAPassingScoreOutsideOneToOneHundred(int passingScore) {
        QuizRequest request = quiz(question("q1", "a", option("a", "A"), option("b", "B")));
        request.setPassingScore(passingScore);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.passingScoreRange");
    }

    @Test
    void rejectsAMissingPassingScore() {
        QuizRequest request = quiz(question("q1", "a", option("a", "A"), option("b", "B")));
        request.setPassingScore(null);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.passingScoreRequired");
    }

    @Test
    void rejectsAQuizWithoutQuestions() {
        QuizRequest request = quiz();

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.questionsRequired");
    }

    @Test
    void rejectsAQuestionWithASingleOption() {
        QuizRequest request = quiz(question("q1", "a", option("a", "A")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.optionsMinimum");
    }

    @Test
    void rejectsAQuestionWithoutText() {
        QuizRequest request = quiz(question("q1", "a", option("a", "A"), option("b", "B")));
        request.getQuestions().getFirst().setText(" ");

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.questionTextRequired");
    }

    @Test
    void rejectsAnOptionWithoutText() {
        QuizRequest request = quiz(question("q1", "a", option("a", ""), option("b", "B")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.optionTextRequired");
    }

    @Test
    void rejectsAnUnknownCorrectOption() {
        QuizRequest request = quiz(question("q1", "does-not-exist", option("a", "A"), option("b", "B")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.correctOptionUnknown");
    }

    @Test
    void rejectsACorrectOptionBorrowedFromAnotherQuestion() {
        QuizRequest request = quiz(
                question("q1", "b1", option("a1", "A"), option("b1", "B")),
                // Points at an option that exists — but on the previous question.
                question("q2", "b1", option("a2", "A"), option("b2", "B")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.correctOptionUnknown");
    }

    @Test
    void rejectsAMissingCorrectOption() {
        QuizRequest request = quiz(question("q1", null, option("a", "A"), option("b", "B")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.correctOptionRequired");
    }

    @Test
    void rejectsAnOptionWithoutAnIdBecauseItCanNeverBeNamedAsTheAnswer() {
        QuizRequest request = quiz(question("q1", "b", option(null, "A"), option("b", "B")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.optionIdRequired");
    }

    @Test
    void rejectsDuplicateOptionIdsWithinAQuestion() {
        QuizRequest request = quiz(question("q1", "a", option("a", "A"), option("a", "Also A")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.optionIdDuplicate");
    }

    @Test
    void rejectsDuplicateQuestionIds() {
        QuizRequest request = quiz(
                question("q1", "a1", option("a1", "A"), option("b1", "B")),
                question("q1", "a2", option("a2", "A"), option("b2", "B")));

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.questionIdDuplicate");
    }

    @Test
    void allowsTheSameOptionReferenceInDifferentQuestions() {
        // References are scoped to their question, so an editor may reuse "option-1" throughout.
        assertThatCode(() -> validator.validate(quiz(
                question("q1", "option-1", option("option-1", "A"), option("option-2", "B")),
                question("q2", "option-2", option("option-1", "A"), option("option-2", "B")))))
                .doesNotThrowAnyException();
    }

    private static QuizRequest quiz(QuizQuestionRequest... questions) {
        return QuizRequest.builder()
                .title("Lesson Quiz")
                .instructions("Choose the correct answer")
                .passingScore(70)
                .questions(new java.util.ArrayList<>(List.of(questions)))
                .build();
    }

    private static QuizQuestionRequest question(String id, String correctOptionId, QuizOptionRequest... options) {
        return QuizQuestionRequest.builder()
                .id(id)
                .text("Question text")
                .correctOptionId(correctOptionId)
                .options(new java.util.ArrayList<>(List.of(options)))
                .build();
    }

    private static QuizOptionRequest option(String id, String text) {
        return QuizOptionRequest.builder().id(id).text(text).build();
    }
}
