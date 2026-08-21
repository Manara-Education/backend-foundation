package com.manara.backend.quiz.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An answer option inside a {@link QuizQuestionRequest}.
 *
 * <p>{@code id} is required and must be unique within its question — it is what
 * {@link QuizQuestionRequest#getCorrectOptionId()} resolves against. For an option that already
 * exists it is the persisted id; for a new one it is any client-generated reference.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizOptionRequest {

    private String id;

    private String text;

    @JsonAlias("order")
    private Integer orderIndex;
}
