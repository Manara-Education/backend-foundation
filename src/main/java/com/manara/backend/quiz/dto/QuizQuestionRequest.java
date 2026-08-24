package com.manara.backend.quiz.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * A question inside a {@link QuizRequest}.
 *
 * <p>{@code id} is a string on purpose. When an editor builds a brand-new quiz its questions and
 * options have no database identity yet, but {@code correctOptionId} still has to point at one of
 * them. Clients therefore send their own reference (a UUID, {@code "option-2"}, anything stable
 * within the request); the server matches ids against the children this question already owns and
 * treats anything else as new. Because the lookup never leaves the question's own children, a
 * request can't reach into another quiz by guessing an id.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizQuestionRequest {

    private String id;

    private String text;

    /** Must reference an option of <em>this</em> question. */
    private String correctOptionId;

    private String explanation;

    private Boolean hintByAiEnabled;

    @JsonAlias("order")
    private Integer orderIndex;

    private List<QuizOptionRequest> options;
}
