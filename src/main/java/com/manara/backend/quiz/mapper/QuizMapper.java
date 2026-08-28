package com.manara.backend.quiz.mapper;

import com.manara.backend.quiz.dto.InstructorQuizQuestionResponse;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.LearnerQuizQuestionResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import com.manara.backend.quiz.dto.LearnerQuizStateResponse;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizOptionResponse;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.model.QuizQuestion;
import com.manara.backend.quiz.service.LearnerQuizState;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The only place quiz entities and quiz response DTOs are built.
 *
 * <p>Note the two response directions: {@link #toInstructorResponse} carries the answer key,
 * {@link #toLearnerResponse} produces a type that structurally cannot.
 */
@Component
public class QuizMapper {

    /**
     * Comparator applied to every collection this mapper returns. Stored order is authoritative and
     * insertion order is never relied upon; the id tie-break keeps the sort total.
     */
    private static final Comparator<QuizQuestion> BY_QUESTION_ORDER =
            Comparator.comparing(QuizQuestion::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(QuizQuestion::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Comparator<QuizOption> BY_OPTION_ORDER =
            Comparator.comparing(QuizOption::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(QuizOption::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Builds a complete quiz graph — questions and options included — before anything is written,
     * so a brand-new quiz never needs a second pass to resolve its answer key.
     */
    public Quiz toQuiz(QuizRequest request, QuizOwnerType ownerType, Long ownerId) {
        Quiz quiz = Quiz.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .title(request.getTitle().trim())
                .instructions(trimToNull(request.getInstructions()))
                .passingScore(request.getPassingScore())
                .build();

        List<QuizQuestionRequest> questions = request.getQuestions();
        for (int i = 0; i < questions.size(); i++) {
            quiz.addQuestion(toQuestion(questions.get(i), quiz, i));
        }
        return quiz;
    }

    public QuizQuestion toQuestion(QuizQuestionRequest request, Quiz quiz, int orderIndex) {
        QuizQuestion question = QuizQuestion.builder()
                .quiz(quiz)
                .text(request.getText().trim())
                .explanation(trimToNull(request.getExplanation()))
                .hintByAiEnabled(Boolean.TRUE.equals(request.getHintByAiEnabled()))
                .orderIndex(orderIndex)
                .build();

        String correctOptionId = request.getCorrectOptionId().trim();
        List<QuizOptionRequest> options = request.getOptions();
        for (int i = 0; i < options.size(); i++) {
            QuizOptionRequest option = options.get(i);
            question.addOption(toOption(option, question, i, correctOptionId.equals(option.getId().trim())));
        }
        return question;
    }

    public QuizOption toOption(QuizOptionRequest request, QuizQuestion question, int orderIndex, boolean correct) {
        return QuizOption.builder()
                .question(question)
                .text(request.getText().trim())
                .orderIndex(orderIndex)
                .correct(correct)
                .build();
    }

    public InstructorQuizResponse toInstructorResponse(Quiz quiz) {
        if (quiz == null) {
            return null;
        }
        return InstructorQuizResponse.builder()
                .id(asId(quiz.getId()))
                .title(quiz.getTitle())
                .instructions(quiz.getInstructions())
                .passingScore(quiz.getPassingScore())
                .questions(sortedQuestions(quiz).map(this::toInstructorQuestionResponse).toList())
                .build();
    }

    public LearnerQuizResponse toLearnerResponse(Quiz quiz) {
        return toLearnerResponse(quiz, null);
    }

    /**
     * @param state where the viewing learner stands on this quiz, or {@code null} when the viewer
     *              is not one — an instructor reading a learner-shaped response, typically
     */
    public LearnerQuizResponse toLearnerResponse(Quiz quiz, LearnerQuizState state) {
        return toLearnerResponse(quiz, state, null);
    }

    /**
     * @param change what to say about this quiz to the learner reading it, or {@code null} where the
     *               question has no answer
     */
    public LearnerQuizResponse toLearnerResponse(Quiz quiz, LearnerQuizState state,
                                                 com.manara.backend.course.dto.ContentChangeResponse change) {
        if (quiz == null) {
            return null;
        }
        return LearnerQuizResponse.builder()
                .id(asId(quiz.getId()))
                .title(quiz.getTitle())
                .instructions(quiz.getInstructions())
                .passingScore(quiz.getPassingScore())
                .questions(sortedQuestions(quiz).map(this::toLearnerQuestionResponse).toList())
                .state(toLearnerQuizStateResponse(state))
                .change(change)
                .build();
    }

    public LearnerQuizStateResponse toLearnerQuizStateResponse(LearnerQuizState state) {
        if (state == null) {
            return null;
        }
        return LearnerQuizStateResponse.builder()
                .available(state.available())
                .attemptCount(state.attemptCount())
                .passed(state.passed())
                .bestScore(state.bestScore())
                .lastAttemptId(state.lastAttemptId())
                .lastSubmittedAt(state.lastSubmittedAt())
                .build();
    }

    private InstructorQuizQuestionResponse toInstructorQuestionResponse(QuizQuestion question) {
        return InstructorQuizQuestionResponse.builder()
                .id(asId(question.getId()))
                .text(question.getText())
                .correctOptionId(correctOptionId(question))
                .explanation(question.getExplanation())
                .hintByAiEnabled(question.getHintByAiEnabled())
                .orderIndex(question.getOrderIndex())
                .options(optionResponses(question))
                .build();
    }

    private LearnerQuizQuestionResponse toLearnerQuestionResponse(QuizQuestion question) {
        return LearnerQuizQuestionResponse.builder()
                .id(asId(question.getId()))
                .text(question.getText())
                .hintByAiEnabled(question.getHintByAiEnabled())
                .orderIndex(question.getOrderIndex())
                .options(optionResponses(question))
                .build();
    }

    private List<QuizOptionResponse> optionResponses(QuizQuestion question) {
        return question.getOptions().stream()
                .sorted(BY_OPTION_ORDER)
                .map(option -> QuizOptionResponse.builder()
                        .id(asId(option.getId()))
                        .text(option.getText())
                        .orderIndex(option.getOrderIndex())
                        .build())
                .toList();
    }

    private String correctOptionId(QuizQuestion question) {
        return question.getOptions().stream()
                .filter(option -> Boolean.TRUE.equals(option.getCorrect()))
                .map(QuizOption::getId)
                .filter(Objects::nonNull)
                .findFirst()
                .map(String::valueOf)
                .orElse(null);
    }

    private java.util.stream.Stream<QuizQuestion> sortedQuestions(Quiz quiz) {
        return quiz.getQuestions().stream().sorted(BY_QUESTION_ORDER);
    }

    private String asId(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
