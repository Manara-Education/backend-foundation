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
 * <p>When {@code locked} is true the viewer has not earned the lesson's content, and the fields
 * that carry it — {@code videoUrl}, {@code description} and {@code quiz} — are absent. What is left
 * is the title, length and position, which is what a locked row in the curriculum shows.
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

    /** True when the viewer may see this lesson listed but not open it. */
    private Boolean locked;

    private LearnerQuizResponse quiz;
    private LocalDateTime createdAt;
}
