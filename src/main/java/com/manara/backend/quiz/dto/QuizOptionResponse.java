package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An answer option as returned by the API.
 *
 * <p>Shared by the instructor and learner representations because it genuinely is the same data:
 * an option carries no answer information. The answer key lives only in
 * {@link InstructorQuizQuestionResponse#getCorrectOptionId()}.
 *
 * <p>{@code id} is always the persisted id, rendered as a string so a client can hand the exact
 * value straight back as a {@code correctOptionId}.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuizOptionResponse {

    private String id;

    private String text;

    private Integer orderIndex;
}
