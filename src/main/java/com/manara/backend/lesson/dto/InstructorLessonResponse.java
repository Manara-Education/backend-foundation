package com.manara.backend.lesson.dto;

import com.manara.backend.quiz.dto.InstructorQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Authoring view of a lesson, used only by the course editor endpoints.
 *
 * <p>The difference that matters is the quiz type: this carries {@link InstructorQuizResponse}
 * with the answer key, {@link LessonResponse} carries the learner view without it.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorLessonResponse {
    private Long id;
    private String title;
    private String summary;
    private String description;
    private String videoUrl;
    private String duration;
    private Integer orderIndex;
    private Long courseId;
    private Long moduleId;
    private InstructorQuizResponse quiz;
    private LocalDateTime createdAt;
}
