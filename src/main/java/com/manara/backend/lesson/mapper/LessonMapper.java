package com.manara.backend.lesson.mapper;

import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LessonMapper {

    private final DurationFormatter durationFormatter;

    public Lesson toLesson(LessonRequest request, Course course) {
        return toLesson(request, course, null, request.getOrderIndex());
    }

    public Lesson toLesson(LessonRequest request, Course course, CourseModule module, Integer orderIndex) {
        return Lesson.builder()
                .title(request.getTitle().trim())
                .summary(request.getSummary())
                .description(request.getDescription())
                .videoUrl(request.getVideoUrl().trim())
                .duration(0)
                .orderIndex(orderIndex)
                .course(course)
                .module(module)
                .build();
    }

    public CompletedLesson toCompletedLesson(Student student, Lesson lesson) {
        return CompletedLesson.builder()
                .student(student)
                .lesson(lesson)
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson) {
        return toLessonResponse(lesson, null, null);
    }

    public LessonResponse toLessonResponse(Lesson lesson, Boolean isCompleted) {
        return toLessonResponse(lesson, isCompleted, null);
    }

    public LessonDetailsResponse.LessonRef toLessonRef(Lesson lesson) {
        if (lesson == null) return null;
        return LessonDetailsResponse.LessonRef.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .build();
    }

    public LessonDetailsResponse toLessonDetailsResponse(Lesson lesson, Boolean isCompleted, LearnerQuizResponse quiz,
                                                         Lesson previous, Lesson next) {
        return LessonDetailsResponse.builder()
                .lesson(toLessonResponse(lesson, isCompleted, quiz))
                .previous(toLessonRef(previous))
                .next(toLessonRef(next))
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson, Boolean isCompleted, LearnerQuizResponse quiz) {
        return LessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoUrl(lesson.getVideoUrl())
                .duration(durationFormatter.formatSeconds(lesson.getDuration()))
                .orderIndex(lesson.getOrderIndex())
                .courseId(lesson.getCourse().getId())
                .moduleId(moduleId(lesson))
                .isCompleted(isCompleted)
                .quiz(quiz)
                .createdAt(lesson.getCreatedAt())
                .build();
    }

    public InstructorLessonResponse toInstructorLessonResponse(Lesson lesson, InstructorQuizResponse quiz) {
        return InstructorLessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoUrl(lesson.getVideoUrl())
                .duration(durationFormatter.formatSeconds(lesson.getDuration()))
                .orderIndex(lesson.getOrderIndex())
                .courseId(lesson.getCourse().getId())
                .moduleId(moduleId(lesson))
                .quiz(quiz)
                .createdAt(lesson.getCreatedAt())
                .build();
    }

    private Long moduleId(Lesson lesson) {
        return lesson.getModule() == null ? null : lesson.getModule().getId();
    }
}
