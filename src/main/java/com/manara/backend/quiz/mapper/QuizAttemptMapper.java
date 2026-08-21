package com.manara.backend.quiz.mapper;

import com.manara.backend.course.model.Course;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.dto.QuizAttemptAnswerResponse;
import com.manara.backend.quiz.dto.QuizAttemptResponse;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.model.QuizAttemptAnswer;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizQuestion;
import com.manara.backend.quiz.service.GradedAnswer;
import com.manara.backend.quiz.service.GradedQuiz;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Builds the attempt aggregate and the result it is reported as.
 *
 * <p>Every figure comes from the {@link GradedQuiz} the grader produced — the mapper copies, it
 * does not compute — so there is exactly one implementation of the scoring rules in the codebase.
 */
@Component
public class QuizAttemptMapper {

    /** Builds the complete attempt graph, answers included, ready to be saved in one pass. */
    public QuizAttempt toQuizAttempt(Quiz quiz, Student student, Course course, int attemptNumber, GradedQuiz graded) {
        QuizAttempt attempt = QuizAttempt.builder()
                .quiz(quiz)
                .student(student)
                .course(course)
                .attemptNumber(attemptNumber)
                .correctCount(graded.correctCount())
                .totalQuestions(graded.totalQuestions())
                .score(graded.score())
                .passingScore(graded.passingScore())
                .passed(graded.passed())
                .build();

        for (GradedAnswer answer : graded.answers()) {
            attempt.addAnswer(toQuizAttemptAnswer(answer));
        }
        return attempt;
    }

    public QuizAttemptAnswer toQuizAttemptAnswer(GradedAnswer answer) {
        return QuizAttemptAnswer.builder()
                .question(answer.question())
                .selectedOption(answer.selectedOption())
                .correct(answer.correct())
                .build();
    }

    /**
     * The result screen's payload. This is the one learner-facing place a correct answer appears,
     * and it is only ever reached by having submitted the quiz it belongs to.
     */
    public QuizAttemptResponse toQuizAttemptResponse(QuizAttempt attempt, GradedQuiz graded) {
        return QuizAttemptResponse.builder()
                .quizId(asId(attempt.getQuiz().getId()))
                .attemptId(attempt.getId())
                .attemptNumber(attempt.getAttemptNumber())
                .correctCount(attempt.getCorrectCount())
                .totalQuestions(attempt.getTotalQuestions())
                .score(attempt.getScore())
                .passingScore(attempt.getPassingScore())
                .passed(attempt.getPassed())
                .submittedAt(attempt.getSubmittedAt())
                .answers(graded.answers().stream().map(this::toAnswerResponse).toList())
                .build();
    }

    private QuizAttemptAnswerResponse toAnswerResponse(GradedAnswer answer) {
        return QuizAttemptAnswerResponse.builder()
                .questionId(asId(answer.question().getId()))
                .selectedOptionId(asId(answer.selectedOption().getId()))
                .correctOptionId(correctOptionId(answer.question()))
                .correct(answer.correct())
                .explanation(answer.question().getExplanation())
                .build();
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

    private String asId(Long id) {
        return id == null ? null : String.valueOf(id);
    }
}
