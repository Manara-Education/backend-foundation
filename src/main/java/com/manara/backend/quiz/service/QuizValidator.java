package com.manara.backend.quiz.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The single place quiz payloads are validated.
 *
 * <p>Every write path runs this — lesson quiz, module exam, final exam, whether it arrives inside a
 * course aggregate or on its own. Keeping the rules here rather than on the DTO means they cannot
 * be bypassed by a missing {@code @Valid} at one of the four places a quiz can appear in a course
 * payload, and it keeps them testable without a Spring context.
 *
 * <p>Errors are reported with 1-based positions so an editor can point at the offending question.
 */
@Component
public class QuizValidator {

    private static final int MIN_PASSING_SCORE = 1;
    private static final int MAX_PASSING_SCORE = 100;
    private static final int MIN_OPTIONS_PER_QUESTION = 2;

    /** Validates a quiz that may be absent — absence means "this owner has no quiz". */
    public void validateIfPresent(QuizRequest request) {
        if (request != null) {
            validate(request);
        }
    }

    public void validate(QuizRequest request) {
        if (isBlank(request.getTitle())) {
            throw new BusinessException("error.quiz.titleRequired");
        }
        validatePassingScore(request.getPassingScore());

        List<QuizQuestionRequest> questions = request.getQuestions();
        if (questions == null || questions.isEmpty()) {
            throw new BusinessException("error.quiz.questionsRequired");
        }

        Set<String> seenQuestionIds = new HashSet<>();
        for (int i = 0; i < questions.size(); i++) {
            validateQuestion(questions.get(i), i + 1, seenQuestionIds);
        }
    }

    private void validatePassingScore(Integer passingScore) {
        if (passingScore == null) {
            throw new BusinessException("error.quiz.passingScoreRequired");
        }
        if (passingScore < MIN_PASSING_SCORE || passingScore > MAX_PASSING_SCORE) {
            throw new BusinessException("error.quiz.passingScoreRange", MIN_PASSING_SCORE, MAX_PASSING_SCORE);
        }
    }

    private void validateQuestion(QuizQuestionRequest question, int position, Set<String> seenQuestionIds) {
        if (question == null) {
            throw new BusinessException("error.quiz.questionMissing", position);
        }
        if (isBlank(question.getText())) {
            throw new BusinessException("error.quiz.questionTextRequired", position);
        }
        if (!isBlank(question.getId()) && !seenQuestionIds.add(question.getId().trim())) {
            throw new BusinessException("error.quiz.questionIdDuplicate", position, question.getId().trim());
        }

        List<QuizOptionRequest> options = question.getOptions();
        if (options == null || options.size() < MIN_OPTIONS_PER_QUESTION) {
            throw new BusinessException("error.quiz.optionsMinimum", position, MIN_OPTIONS_PER_QUESTION);
        }

        Set<String> optionIds = new HashSet<>();
        for (QuizOptionRequest option : options) {
            validateOption(option, position, optionIds);
        }

        validateCorrectOption(question, position, optionIds);
    }

    private void validateOption(QuizOptionRequest option, int questionPosition, Set<String> optionIds) {
        if (option == null || isBlank(option.getId())) {
            // Without a reference of its own an option can never be named as the correct answer.
            throw new BusinessException("error.quiz.optionIdRequired", questionPosition);
        }
        if (isBlank(option.getText())) {
            throw new BusinessException("error.quiz.optionTextRequired", questionPosition);
        }
        if (!optionIds.add(option.getId().trim())) {
            throw new BusinessException("error.quiz.optionIdDuplicate", questionPosition, option.getId().trim());
        }
    }

    /**
     * The rule that makes cross-question answer keys impossible: the reference is only ever matched
     * against the ids collected from this question's own options, so an id belonging to another
     * question — or to nothing at all — is rejected the same way.
     */
    private void validateCorrectOption(QuizQuestionRequest question, int position, Set<String> optionIds) {
        String correctOptionId = question.getCorrectOptionId();
        if (isBlank(correctOptionId)) {
            throw new BusinessException("error.quiz.correctOptionRequired", position);
        }
        if (!optionIds.contains(correctOptionId.trim())) {
            throw new BusinessException("error.quiz.correctOptionUnknown", position, correctOptionId.trim());
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
