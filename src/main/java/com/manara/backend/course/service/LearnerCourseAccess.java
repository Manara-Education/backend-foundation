package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The gate every learner-facing course request passes through.
 *
 * <p>Answers two questions once, in one place: may this person see this course at all, and how much
 * of it may they be served. Publication decides visibility; the learner's entitlement decides
 * content. Both used to be re-checked ad hoc at each endpoint, which is how a published course ended
 * up handing its lesson videos to anyone who could guess an id.

 * <p>Enrolment on its own no longer opens anything. It says the learner joined; whether they may
 * currently read the course is {@link EntitlementPolicy}'s answer, so a subscription that lapsed
 * closes the content everywhere at once rather than at whichever endpoints remembered to look.
 *
 * <p>Three outcomes, and nothing in between:
 *
 * <ul>
 *   <li><strong>Entitled learner</strong> — the curriculum decides what is open, tracked against
 *       their own progress.</li>
 *   <li><strong>Enrolled learner whose access lapsed</strong> — their progress, unchanged, with
 *       nothing open. Renewing puts them back where they were.</li>
 *   <li><strong>Owning instructor</strong> — everything open, including a draft, because it is
 *       theirs. No progress, because they are not taking the course.</li>
 *   <li><strong>Anyone else signed in</strong> — the shape of a published course and none of its
 *       content, which is exactly what the discovery screen shows.</li>
 * </ul>
 *
 * <p>Whether the course resolves at all is {@link CourseVisibility}'s answer, not a publication
 * check made here. Unpublishing takes a course off the catalogue; it does not take it away from the
 * learners who already hold it, and every one of the outcomes above still applies while it is a
 * draft.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LearnerCourseAccess {

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseAggregateLoader courseAggregateLoader;
    private final CourseProgressionService courseProgressionService;
    private final EntitlementPolicy entitlementPolicy;
    private final CourseVisibility courseVisibility;

    /**
     * For write paths — completing a lesson, submitting a quiz — where anything short of an
     * enrolled learner is an error rather than a reduced view.
     */
    public CourseViewer requireEnrolled(User user, Long courseId) {
        if (user == null || user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        Course course = courseVisibility.requireVisible(user, courseId);
        Student student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));
        Enrollment enrollment = enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId())
                .orElseThrow(() -> new BusinessException("error.course.notEnrolled"));

        // Joining and being allowed in are different facts. A lapsed subscriber keeps the enrolment
        // above and everything recorded against it, and is still refused here.
        if (!entitlementPolicy.isEntitled(courseId, student.getId())) {
            throw new BusinessException("error.course.accessExpired");
        }

        CourseAggregate aggregate = courseAggregateLoader.load(course);
        return new CourseViewer(course, student, enrollment, aggregate,
                courseProgressionService.progressionOf(aggregate, student));
    }

    /**
     * For read paths, which serve every audience — the response is narrowed by the resolved
     * progression instead of the request being rejected.
     */
    public CourseViewer resolveViewer(User user, Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        if (isOwningInstructor(user, course)) {
            CourseAggregate aggregate = courseAggregateLoader.load(course);
            return new CourseViewer(course, null, null, aggregate, CourseProgression.forOwner(aggregate));
        }

        // A draft is the instructor's work in progress; to everyone who does not already hold the
        // course it does not exist. To a learner who does, withdrawing it from the catalogue is not
        // a revocation — they keep the course they joined. See CourseVisibility.
        if (!courseVisibility.isVisibleTo(user, course)) {
            throw new ResourceNotFoundException("error.course.notFound", courseId.toString());
        }

        CourseAggregate aggregate = courseAggregateLoader.load(course);

        Optional<Student> student = findStudent(user);
        Optional<Enrollment> enrollment = student
                .flatMap(s -> enrollmentRepository.findByCourseIdAndStudentId(courseId, s.getId()));
        if (enrollment.isEmpty()) {
            return new CourseViewer(course, student.orElse(null), null, aggregate, CourseProgression.forVisitor());
        }

        CourseProgression progression = courseProgressionService.progressionOf(aggregate, student.get());
        if (!entitlementPolicy.isEntitled(courseId, student.get().getId())) {
            // Read paths narrow rather than refuse: the learner still sees their course and their
            // progress, and every lesson in it is locked until they renew.
            progression = progression.suspended();
        }

        return new CourseViewer(course, student.get(), enrollment.get(), aggregate, progression);
    }

    private boolean isOwningInstructor(User user, Course course) {
        return user != null
                && user.getRole() == Role.INSTRUCTOR
                && course.getInstructor().getUser().getId().equals(user.getId());
    }

    private Optional<Student> findStudent(User user) {
        if (user == null || user.getRole() != Role.STUDENT) {
            return Optional.empty();
        }
        return studentRepository.findByUserId(user.getId());
    }
}
