package com.manara.backend.course.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonResponse {
    private Long id;
    private String title;
    private String summary;
    private String description;
    private String videoId;
    private Integer duration;
    private Integer orderIndex;
    private Long courseId;
    private Boolean isCompleted; // Included if returned in a student context
    private LocalDateTime createdAt;
}
