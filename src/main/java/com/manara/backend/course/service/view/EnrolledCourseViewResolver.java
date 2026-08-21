package com.manara.backend.course.service.view;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class EnrolledCourseViewResolver implements CourseDetailsViewResolver {

    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CompletedLessonRepository completedLessonRepository;

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.ENROLLED;
    }

    @Override
    public Set<Long> resolveCompletedLessonIds(User user, Long courseId) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId())
                .orElseThrow(() -> new BusinessException("error.course.notEnrolled"));

        return Set.copyOf(
                completedLessonRepository.findCompletedLessonIdsByStudentIdAndCourseId(student.getId(), courseId));
    }
}
