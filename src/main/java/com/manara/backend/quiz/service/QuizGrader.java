package com.manara.backend.quiz.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.quiz.dto.QuizAnswerRequest;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizQuestion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grades a submission against the stored quiz.
 *
 * <p>Pure and side-effect free: it reads the quiz graph, resolves the submitted references against
 * it and returns the result. Persisting the attempt and deciding who was allowed to submit belong
 * to {@code QuizAttemptService}; keeping them apart is what makes the scoring rules testable on
 * their own.
 *
 * <p>Two rules make a forged submission impossible. An option is only ever resolved against the
 * options of the question it was sent for, so naming another question's option — or another quiz's
 * — is rejected rather than counted. And the answer key is read from the stored
 * {@link QuizOption#getCorrect()} flag, never from anything the request carries.
 */
@Component
public class QuizGrader {

    public GradedQuiz grade(Quiz quiz, List<QuizAnswerRequest> submitted) {
        List<QuizQuestion> questions = quiz.getQuestions();
        if (questions.isEmpty()) {
            // QuizValidator refuses to store a quiz without questions, so this only guards data
            // that predates it — grading it would mean dividing by zero.
            throw new BusinessException("error.quiz.attemptQuestionsMissing");
        }
        if (submitted == null || submitted.isEmpty()) {
            throw new BusinessException("error.quiz.attemptAnswersRequired");
        }

        Map<Long, QuizOption> chosenByQuestionId = resolveChoices(questions, submitted);

        List<GradedAnswer> graded = new ArrayList<>(questions.size());
        int correctCount = 0;
        for (QuizQuestion question : questions) {
            QuizOption chosen = chosenByQuestionId.get(question.getId());
            if (chosen == null) {
                // The product submits a completed quiz in one shot, so a missing answer is a
                // malformed request rather than a zero-scoring question.
                throw new BusinessException("error.quiz.attemptQuestionUnanswered", question.getId());
            }
            boolean correct = Boolean.TRUE.equals(chosen.getCorrect());
            if (correct) {
                correctCount++;
            }
            graded.add(new GradedAnswer(question, chosen, correct));
        }

        int total = questions.size();
        int score = (int) Math.round(correctCount * 100.0 / total);
        int passingScore = quiz.getPassingScore();

        return new GradedQuiz(correctCount, total, score, passingScore, score >= passingScore, graded);
    }

    /**
     * Matches every submitted answer to a question of this quiz and an option of that question.
     * Anything that does not resolve inside the quiz is an error — never a wrong answer.
     */
    private Map<Long, QuizOption> resolveChoices(List<QuizQuestion> questions, List<QuizAnswerRequest> submitted) {
        Map<Long, QuizQuestion> questionsById = new LinkedHashMap<>();
        for (QuizQuestion question : questions) {
            questionsById.put(question.getId(), question);
        }

        Map<Long, QuizOption> chosen = new LinkedHashMap<>();
        for (QuizAnswerRequest answer : submitted) {
            if (answer == null) {
                throw new BusinessException("error.quiz.attemptAnswersRequired");
            }
            Long questionId = parseId(answer.getQuestionId(), "error.quiz.attemptQuestionUnknown");
            QuizQuestion question = questionsById.get(questionId);
            if (question == null) {
                throw new BusinessException("error.quiz.attemptQuestionUnknown", answer.getQuestionId());
            }
            if (chosen.containsKey(questionId)) {
                throw new BusinessException("error.quiz.attemptQuestionDuplicate", questionId);
            }
            chosen.put(questionId, resolveOption(question, answer.getOptionId()));
        }
        return chosen;
    }

    private QuizOption resolveOption(QuizQuestion question, String optionId) {
        Long id = parseId(optionId, "error.quiz.attemptOptionUnknown", question.getId());
        return question.getOptions().stream()
                .filter(option -> id.equals(option.getId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        "error.quiz.attemptOptionUnknown", optionId, question.getId()));
    }

    /**
     * Ids travel as strings because that is how the learner quiz payload renders them. A value that
     * is not a number cannot match anything, so it is reported the same way an unknown id is.
     */
    private Long parseId(String value, String errorCode, Object... trailingArgs) {
        String reported = value == null ? "" : value.trim();
        try {
            if (reported.isEmpty()) {
                throw new NumberFormatException();
            }
            return Long.valueOf(reported);
        } catch (NumberFormatException ex) {
            throw new BusinessException(errorCode, prepend(reported, trailingArgs));
        }
    }

    private Object[] prepend(Object first, Object[] rest) {
        Object[] args = new Object[rest.length + 1];
        args[0] = first;
        System.arraycopy(rest, 0, args, 1, rest.length);
        return args;
    }
}
