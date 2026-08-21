package com.manara.backend.lesson.dto;

import com.manara.backend.quiz.dto.LearnerQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learner-facing lesson. The attached quiz is the {@link LearnerQuizResponse} view, so course and
 * lesson browsing can never hand out an answer key.
 *
 * @see InstructorLessonResponse for the authoring view
 */
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
    private Long moduleId;
    private Boolean isCompleted;
    private LearnerQuizResponse quiz;
    private LocalDateTime createdAt;
}
