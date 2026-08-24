package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.LessonResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary shape used by the course list endpoints. The new fields are additive — {@code price} is
 * still populated from the course's purchase price so existing clients keep working.
 */
@Getter
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
    private BigDecimal purchasePrice;
    private CourseAccessType accessType;
    private CourseStructure structure;
    private CourseStatus status;
    private Integer studentsCount;
    private Long instructorId;
    private String instructorName;
    private LocalDateTime createdAt;
    private List<LessonResponse> lessons;
}
