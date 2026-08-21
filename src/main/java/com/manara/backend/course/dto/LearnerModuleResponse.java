package com.manara.backend.course.dto;

import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Learner view of a module — no answer keys anywhere in the tree.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearnerModuleResponse {

    private Long id;
    private String title;
    private String description;
    private Integer orderIndex;
    private List<LessonResponse> lessons;
    private LearnerQuizResponse quiz;
}
