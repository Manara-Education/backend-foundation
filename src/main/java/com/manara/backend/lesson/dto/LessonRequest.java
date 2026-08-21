package com.manara.backend.lesson.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.manara.backend.quiz.dto.QuizRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A lesson, either on its own (instructor lesson endpoints) or nested inside a course payload.
 *
 * <p>The Bean Validation annotations apply to the standalone endpoints, which pass this through
 * {@code @Valid}. Inside a course payload the aggregate validator takes over: {@code orderIndex} is
 * ignored there because position in the array is the authoritative order, and {@code id} decides
 * whether an existing lesson is updated or a new one created.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonRequest {

    /** Set to update an existing lesson of the course; omit to create a new one. */
    private Long id;

    @NotBlank(message = "{validation.lesson.title.required}")
    private String title;

    private String summary;

    private String description;

    @NotBlank(message = "{validation.lesson.videoUrl.required}")
    private String videoUrl;

    @NotNull(message = "{validation.lesson.orderIndex.required}")
    @JsonAlias("order")
    private Integer orderIndex;

    /**
     * Module this lesson belongs to. Required by the standalone endpoints when the course uses
     * modules; inside a course payload the nesting already says it, and this field is ignored.
     */
    private Long moduleId;

    /** Optional — {@code null} removes the lesson's quiz. */
    private QuizRequest quiz;
}
