package com.manara.backend.course.dto;

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
public class CourseDetailsResponse {

    private CourseInfo course;
    private InstructorInfo instructor;
    private List<LessonResponse> lessons;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CourseInfo {
        private Long id;
        private String title;
        private String subtitle;
        private String image;
        private String description;
        private Integer duration;
        private Integer lessonCount;
        private BigDecimal price;
        private Integer studentsCount;
        private LocalDateTime createdAt;
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InstructorInfo {
        private Long id;
        private String fullName;
        private String email;
        private String bio;
        private String specialization;
    }
}
