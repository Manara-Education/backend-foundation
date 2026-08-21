package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The graded result of one submission.
 *
 * <p>Everything the result screen needs is server-computed and present here — the score, the pass
 * mark it was measured against, the verdict, and the per-question review — so the client never has
 * to hold an answer key or reproduce a scoring rule to render it.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuizAttemptResponse {

    private String quizId;

    private Long attemptId;

    /** 1 for the learner's first submission of this quiz. */
    private Integer attemptNumber;

    private Integer correctCount;

    private Integer totalQuestions;

    /** Percentage answered correctly, 0-100. */
    private Integer score;

    private Integer passingScore;

    private Boolean passed;

    private LocalDateTime submittedAt;

    private List<QuizAttemptAnswerResponse> answers;
}
