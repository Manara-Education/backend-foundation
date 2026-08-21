package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Learner-facing view of a quiz: the questions and options needed to attempt it, and nothing that
 * would give the answer away.
 *
 * <p>This type has no field capable of carrying the answer key, so no serialization setting,
 * nested object or future mapper change can leak one.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearnerQuizResponse {

    private String id;

    private String title;

    private String instructions;

    private Integer passingScore;

    private List<LearnerQuizQuestionResponse> questions;

    /**
     * Where this learner stands on the quiz — whether the curriculum has opened it, and how earlier
     * attempts went. Null when the viewer is not a learner of the course.
     */
    private LearnerQuizStateResponse state;
}
