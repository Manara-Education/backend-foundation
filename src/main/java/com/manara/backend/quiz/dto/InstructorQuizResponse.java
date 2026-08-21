package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Authoring view of a quiz: everything needed to reconstruct the editor state, answer key included.
 *
 * <p>Only ever returned from instructor/admin endpoints. The learner equivalent is
 * {@link LearnerQuizResponse}, a separate type with no answer fields at all — a separate class
 * rather than a nulled-out field, so an answer cannot leak through a mapping mistake.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorQuizResponse {

    private String id;

    private String title;

    private String instructions;

    private Integer passingScore;

    private List<InstructorQuizQuestionResponse> questions;
}
