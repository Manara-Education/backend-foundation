package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A question as presented to a learner before submission.
 *
 * <p>Neither {@code correctOptionId} nor {@code explanation} exists here. Both belong to the
 * result of an attempt, which this codebase does not model yet.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearnerQuizQuestionResponse {

    private String id;

    private String text;

    private Boolean hintByAiEnabled;

    private Integer orderIndex;

    private List<QuizOptionResponse> options;
}
