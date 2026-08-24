package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * A learner's completed quiz.
 *
 * <p>The payload carries answers and nothing else. A score, a pass flag or an answer key sent by a
 * client would be ignored — there is deliberately no field able to receive one.
 *
 * <p>Like {@code QuizRequest}, the shape carries no Bean Validation annotations: the rules that
 * matter here are relational (does this question belong to this quiz, does this option belong to
 * that question) and live in {@code QuizGrader}, which every submission runs through.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizSubmissionRequest {

    private List<QuizAnswerRequest> answers;
}
