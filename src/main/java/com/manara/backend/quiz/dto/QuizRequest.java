package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * One request shape for every quiz in the product — lesson quiz, module exam, course final exam.
 *
 * <p>Deliberately carries no Bean Validation annotations. A quiz can arrive at four different
 * places in a course payload, and a single forgotten {@code @Valid} would silently disable the
 * rules at one of them. All quiz rules live in {@code QuizValidator} instead, which every write
 * path runs.
 *
 * <p>{@code id} is accepted for symmetry with the response but carries no meaning: a quiz is
 * identified by its owner, which the URL and the surrounding payload already establish.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizRequest {

    private String id;

    private String title;

    private String instructions;

    private Integer passingScore;

    private List<QuizQuestionRequest> questions;
}
