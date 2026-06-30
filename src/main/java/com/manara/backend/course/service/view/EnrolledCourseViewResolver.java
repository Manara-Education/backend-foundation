package com.manara.backend.course.service.view;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class EnrolledCourseViewResolver implements CourseDetailsViewResolver {

    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CompletedLessonRepository completedLessonRepository;
    private final LessonMapper lessonMapper;

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.ENROLLED;
    }

    @Override
    public List<LessonResponse> resolveLessons(User user, Long courseId, List<Lesson> lessons) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId())
                .orElseThrow(() -> new BusinessException("error.course.notEnrolled"));

        Set<Long> completedIds = Set.copyOf(
                completedLessonRepository.findCompletedLessonIdsByStudentIdAndCourseId(student.getId(), courseId));

        return lessons.stream()
                .map(lesson -> lessonMapper.toLessonResponse(lesson, completedIds.contains(lesson.getId())))
                .collect(Collectors.toList());
    }
}
