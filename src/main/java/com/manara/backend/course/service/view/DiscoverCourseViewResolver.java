package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseProgressionService;
import com.manara.backend.course.service.EntitlementPolicy;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class DiscoverCourseViewResolver implements CourseDetailsViewResolver {

    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseProgressionService courseProgressionService;
    private final EntitlementPolicy entitlementPolicy;

    @Override
    public CourseViewMode mode() {
        return CourseViewMode.DISCOVER;
    }

    /**
     * Discovery is the shop window: anyone signed in may see what the course contains, nobody may
     * open any of it, and there is no progress to report because nobody is enrolled yet.
     *
     * <p>With one exception — someone who already holds the course. Reaching it from the catalogue
     * rather than from their own list does not take their access away, and showing them their own
     * course padlocked, with an offer to buy what they own, would be a plain lie. They get exactly
     * the view the enrolled screen would give them.
     */
    @Override
    public CourseProgression resolveProgression(User user, CourseAggregate aggregate) {
        Long courseId = aggregate.course().getId();

        return findLearner(user)
                .filter(student -> entitlementPolicy.isEntitled(courseId, student.getId()))
                .filter(student -> enrollmentRepository.existsByCourseIdAndStudentId(courseId, student.getId()))
                .map(student -> courseProgressionService.progressionOf(aggregate, student))
                .orElseGet(CourseProgression::forVisitor);
    }

    private Optional<Student> findLearner(User user) {
        if (user == null || user.getRole() != Role.STUDENT) {
            return Optional.empty();
        }
        return studentRepository.findByUserId(user.getId());
    }
}
