package com.manara.backend.course.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseRequest {

    @NotBlank(message = "{validation.course.title.required}")
    private String title;

    private String subtitle;

    private String image;

    private String description;

    @Positive(message = "{validation.course.duration.positive}")
    private Integer duration;

    @NotNull(message = "{validation.course.price.required}")
    @Positive(message = "{validation.course.price.positive}")
    private BigDecimal price;
}
