package com.manara.backend.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonRequest {

    @NotBlank(message = "{validation.lesson.title.required}")
    private String title;

    private String summary;

    private String description;

    @NotBlank(message = "{validation.lesson.videoId.required}")
    private String videoId;

    private Integer duration;

    @NotNull(message = "{validation.lesson.orderIndex.required}")
    private Integer orderIndex;
}
