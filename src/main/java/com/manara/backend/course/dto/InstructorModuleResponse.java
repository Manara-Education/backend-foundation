package com.manara.backend.course.dto;

import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Authoring view of a module — lessons and exam carry the answer key.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorModuleResponse {

    private Long id;
    private String title;
    private String description;
    private Integer orderIndex;
    private List<InstructorLessonResponse> lessons;
    private InstructorQuizResponse quiz;
}
