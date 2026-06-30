package com.manara.backend.lesson.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonResponse {
    private Long id;
    private String title;
    private String summary;
    private String description;
    private String videoUrl;
    private String duration;
    private Integer orderIndex;
    private Long courseId;
    private Boolean isCompleted;
    private LocalDateTime createdAt;
}
