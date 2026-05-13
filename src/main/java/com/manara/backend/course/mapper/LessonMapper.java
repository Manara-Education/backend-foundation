package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.LessonRequest;
import com.manara.backend.course.dto.LessonResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Lesson;
import org.springframework.stereotype.Component;

@Component
public class LessonMapper {

    public Lesson toLesson(LessonRequest request, Course course) {
        return Lesson.builder()
                .title(request.getTitle())
                .summary(request.getSummary())
                .description(request.getDescription())
                .videoId(request.getVideoId())
                .duration(request.getDuration())
                .orderIndex(request.getOrderIndex())
                .course(course)
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson) {
        return toLessonResponse(lesson, false);
    }

    public LessonResponse toLessonResponse(Lesson lesson, boolean isCompleted) {
        return LessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoId(lesson.getVideoId())
                .duration(lesson.getDuration())
                .orderIndex(lesson.getOrderIndex())
                .courseId(lesson.getCourse().getId())
                .isCompleted(isCompleted)
                .createdAt(lesson.getCreatedAt())
                .build();
    }
}
