package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.LessonResponse;
import com.manara.backend.course.model.Lesson;
import com.manara.backend.user.model.User;

import java.util.List;

public interface CourseDetailsViewResolver {

    CourseViewMode mode();

    List<LessonResponse> resolveLessons(User user, Long courseId, List<Lesson> lessons);
}
