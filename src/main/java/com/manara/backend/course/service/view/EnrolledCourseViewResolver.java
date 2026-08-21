package com.manara.backend.course.service.view;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseProgressionService;
import com.manara.backend.course.service.EntitlementPolicy;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EnrolledCourseViewResolver implements CourseDetailsViewResolver {

    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseProgressionService courseProgressionService;
    private final EntitlementPolicy entitlementPolicy;

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.ENROLLED;
    }

    @Override
    public CourseProgression resolveProgression(User user, CourseAggregate aggregate) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        Long courseId = aggregate.course().getId();
        enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId())
                .orElseThrow(() -> new BusinessException("error.course.notEnrolled"));

        var progression = courseProgressionService.progressionOf(aggregate, student);

        // A lapsed subscriber still owns this screen — their progress, their history, their
        // renewal offer. What they no longer own is the content, so everything in it locks.
        return entitlementPolicy.isEntitled(courseId, student.getId()) ? progression : progression.suspended();
    }
}
