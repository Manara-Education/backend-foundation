package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class DiscoverCourseViewResolver implements CourseDetailsViewResolver {

    private final LessonMapper lessonMapper;

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.DISCOVER;
    }

    @Override
    public List<LessonResponse> resolveLessons(User user, Long courseId, List<Lesson> lessons) {
        return lessons.stream()
                .map(lessonMapper::toLessonResponse)
                .collect(Collectors.toList());
    }
}
