package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

@Component
public class DiscoverCourseViewResolver implements CourseDetailsViewResolver {

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.DISCOVER;
    }

    /**
     * Discovery is the shop window: anyone signed in may see what the course contains, nobody may
     * open any of it, and there is no progress to report because nobody is enrolled yet.
     */
    @Override
    public CourseProgression resolveProgression(User user, CourseAggregate aggregate) {
        return CourseProgression.forVisitor();
    }
}
