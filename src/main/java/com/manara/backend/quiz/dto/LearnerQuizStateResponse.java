package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Where one learner stands on one quiz: whether they may take it yet, and how their past attempts
 * went.
 *
 * <p>This is what lets the quiz screen open in the right state without the client deriving
 * progression rules of its own — {@code available} answers "is this exam still locked", and
 * {@code passed} plus {@code bestScore} answer "have I already cleared this".
 *
 * <p>Carries no answer key: it summarises results, it does not review them. The review belongs to
 * {@link QuizAttemptResponse}, which only a submission produces.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearnerQuizStateResponse {

    /** False while the progression rules still gate this quiz. */
    private Boolean available;

    private Integer attemptCount;

    private Boolean passed;

    /** Highest score reached so far, or {@code null} when the learner has not attempted it. */
    private Integer bestScore;

    private Long lastAttemptId;

    private LocalDateTime lastSubmittedAt;
}
