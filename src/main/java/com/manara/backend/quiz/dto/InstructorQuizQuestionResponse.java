package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A question as seen by its author — carries the answer key and the explanation.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorQuizQuestionResponse {

    private String id;

    private String text;

    /** Persisted id of the correct option, ready to be sent straight back on the next update. */
    private String correctOptionId;

    private String explanation;

    private Boolean hintByAiEnabled;

    private Integer orderIndex;

    private List<QuizOptionResponse> options;
}
