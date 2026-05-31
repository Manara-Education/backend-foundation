package com.manara.backend.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseViewResponse {
    private Long id;
    private String title;
    private String instructor;
    private String description;
    private String image;
    private Integer progress;
    private Integer totalLessons;
    private Integer completedLessons;
    private CourseStatus status;
    private String category;
    private String duration;
}
