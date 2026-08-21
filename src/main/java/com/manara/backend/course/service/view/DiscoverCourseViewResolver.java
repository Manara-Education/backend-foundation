package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class DiscoverCourseViewResolver implements CourseDetailsViewResolver {

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.DISCOVER;
    }

    /**
     * Discovery shows the course to anyone signed in and carries no progress, so there is nothing
     * to resolve.
     */
    @Override
    public Set<Long> resolveCompletedLessonIds(User user, Long courseId) {
        return null;
    }
}
