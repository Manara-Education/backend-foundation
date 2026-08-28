package com.manara.backend.course.service;

import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether a course exists as far as one viewer is concerned.
 *
 * <h2>Publication is acquisition, not access</h2>
 * Unpublishing withdraws a course from the catalogue. It is not a revocation: the content, the
 * enrolments, the entitlements, the progress and the attempt history all stay exactly as they were,
 * and {@code CourseService#unpublish} has said so in its own documentation from the start. The
 * implementation did not agree with it. Every learner-facing lookup gated on
 * {@code status == PUBLISHED} alone, so an instructor taking a course down for a week answered
 * {@code 404} to the learners already studying it — their library entry, their curriculum and their
 * next lesson, all gone until it came back.
 *
 * <p>The two questions are separated here and answered once:
 *
 * <ul>
 *   <li><strong>May this person acquire or discover the course?</strong> Only while it is
 *       published. The catalogue, the discovery screen and checkout all keep asking that, and none
 *       of them goes through this class — a draft is invisible to anyone who does not already hold
 *       it, and stays that way.
 *   <li><strong>May this person open a course they already hold?</strong> Yes, whatever its
 *       publication state. That is what this class adds.
 * </ul>
 *
 * <p>Already holding it means having an {@code Enrollment}, not a currently valid entitlement.
 * A lapsed subscriber's course must still resolve, or renewing would mean finding a course that no
 * longer exists for them; what they may <em>read</em> is {@link EntitlementPolicy}'s answer, applied
 * afterwards, and it is unchanged by any of this.
 *
 * <p>The owning instructor sees their own draft, which is the whole point of a draft.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseVisibility {

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;

    /**
     * The course, if this viewer may see it at all.
     *
     * @throws ResourceNotFoundException when they may not — the same answer a course that does not
     *         exist gets, so a draft cannot be probed for by id.
     */
    public Course requireVisible(User user, Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        if (isVisibleTo(user, course)) {
            return course;
        }
        throw new ResourceNotFoundException("error.course.notFound", courseId.toString());
    }

    public boolean isVisibleTo(User user, Course course) {
        return course.getStatus() == CourseStatus.PUBLISHED
                || isOwningInstructor(user, course)
                || alreadyHolds(user, course.getId());
    }

    private boolean isOwningInstructor(User user, Course course) {
        return user != null
                && user.getRole() == Role.INSTRUCTOR
                && course.getInstructor().getUser().getId().equals(user.getId());
    }

    private boolean alreadyHolds(User user, Long courseId) {
        if (user == null || user.getRole() != Role.STUDENT) {
            return false;
        }
        return studentRepository.findByUserId(user.getId())
                .map(student -> enrollmentRepository.existsByCourseIdAndStudentId(courseId, student.getId()))
                .orElse(false);
    }
}
