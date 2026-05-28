package com.manara.backend.dashboard.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.repository.CompletedLessonRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.repository.LessonRepository;
import com.manara.backend.dashboard.dto.CourseViewResponse;
import com.manara.backend.dashboard.mapper.DashboardMapper;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final CompletedLessonRepository completedLessonRepository;
    private final StudentRepository studentRepository;
    private final DashboardMapper dashboardMapper;

    public List<CourseViewResponse> getStudentCourses(User user) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "error.profile.studentNotFound", user.getId().toString()));

        return enrollmentRepository.findByStudentId(student.getId()).stream()
                .map(enrollment -> {
                    Long courseId = enrollment.getCourse().getId();
                    int total = lessonRepository.countByCourseId(courseId);
                    int completed = completedLessonRepository
                            .countByStudentIdAndLesson_Course_Id(student.getId(), courseId);
                    return dashboardMapper.toCourseViewResponse(enrollment, total, completed);
                })
                .collect(Collectors.toList());
    }
}
