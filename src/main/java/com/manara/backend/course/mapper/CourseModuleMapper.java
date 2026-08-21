package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import org.springframework.stereotype.Component;

@Component
public class CourseModuleMapper {

    public CourseModule toCourseModule(ModuleRequest request, Course course, int orderIndex) {
        return CourseModule.builder()
                .course(course)
                .title(request.getTitle().trim())
                .description(trimToNull(request.getDescription()))
                .orderIndex(orderIndex)
                .build();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
