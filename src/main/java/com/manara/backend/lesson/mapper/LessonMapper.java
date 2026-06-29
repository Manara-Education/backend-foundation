package com.manara.backend.lesson.mapper;

import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.model.Course;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.profile.model.Student;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LessonMapper {

    private final DurationFormatter durationFormatter;

    public Lesson toLesson(LessonRequest request, Course course) {
        return Lesson.builder()
                .title(request.getTitle())
                .summary(request.getSummary())
                .description(request.getDescription())
                .videoUrl(request.getVideoUrl())
                .duration(0)
                .orderIndex(request.getOrderIndex())
                .course(course)
                .build();
    }

    public CompletedLesson toCompletedLesson(Student student, Lesson lesson) {
        return CompletedLesson.builder()
                .student(student)
                .lesson(lesson)
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson) {
        return toLessonResponse(lesson, null);
    }

    public LessonDetailsResponse.LessonRef toLessonRef(Lesson lesson) {
        if (lesson == null) return null;
        return LessonDetailsResponse.LessonRef.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .build();
    }

    public LessonDetailsResponse toLessonDetailsResponse(Lesson lesson, Boolean isCompleted, Lesson previous, Lesson next) {
        return LessonDetailsResponse.builder()
                .lesson(toLessonResponse(lesson, isCompleted))
                .previous(toLessonRef(previous))
                .next(toLessonRef(next))
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson, Boolean isCompleted) {
        return LessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoUrl(lesson.getVideoUrl())
                .duration(durationFormatter.formatSeconds(lesson.getDuration()))
                .orderIndex(lesson.getOrderIndex())
                .courseId(lesson.getCourse().getId())
                .isCompleted(isCompleted)
                .createdAt(lesson.getCreatedAt())
                .build();
    }
}
