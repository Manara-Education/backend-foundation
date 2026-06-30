package com.manara.backend.course.dto;

import com.manara.backend.lesson.dto.LessonResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseResponse {
    private Long id;
    private String title;
    private String subtitle;
    private String image;
    private String description;
    private Integer duration;
    private Integer lessonCount;
    private BigDecimal price;
    private Integer studentsCount;
    private Long instructorId;
    private String instructorName;
    private LocalDateTime createdAt;
    private List<LessonResponse> lessons;
}
