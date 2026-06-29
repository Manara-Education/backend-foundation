package com.manara.backend.lesson.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonRequest {

    @NotBlank(message = "{validation.lesson.title.required}")
    private String title;

    private String summary;

    private String description;

    @NotBlank(message = "{validation.lesson.videoUrl.required}")
    private String videoUrl;

    @NotNull(message = "{validation.lesson.orderIndex.required}")
    private Integer orderIndex;
}
