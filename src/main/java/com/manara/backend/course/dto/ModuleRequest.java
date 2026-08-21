package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * A module inside a course payload.
 *
 * <p>Order comes from the module's position in the array, not from a submitted value — that keeps
 * the stored order deterministic even when a client sends duplicate or missing indexes.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ModuleRequest {

    /** Set to update an existing module of this course; omit to create a new one. */
    private Long id;

    private String title;

    private String description;

    /**
     * Accepted so the payload round-trips unchanged, but position in the array is what gets stored.
     * Lessons, questions and options follow the same rule.
     */
    @JsonAlias("order")
    private Integer orderIndex;

    private List<LessonRequest> lessons;

    /** The module exam. Optional — {@code null} removes it. */
    private QuizRequest quiz;
}
