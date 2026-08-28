package com.manara.backend.course.service;

import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
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
 * <p>Named {@code CourseViewPolicy} rather than {@code CourseVisibility}, which is what it was
 * called until {@link CourseVisibility} became a domain value on the course itself. Two different
 * meanings of one word in one package is how a rule ends up applied to the wrong thing; the enum
 * owns the noun, and this — the policy that reads it — owns a name that says what it does.
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
 *       {@linkplain Course#isDiscoverable() discoverable}. The catalogue, the discovery screen and
 *       checkout all keep asking that, and none of them goes through this class — a draft and a
 *       private course are both invisible to anyone who does not already hold one, and stay that
 *       way.
 *   <li><strong>May this person open a course they already hold?</strong> Yes, whatever its
 *       publication state and whatever its visibility. That is what this class adds.
 * </ul>
 *
 * <p>Already holding it means having an {@code Enrollment}, not a currently valid entitlement.
 * A lapsed subscriber's course must still resolve, or renewing would mean finding a course that no
 * longer exists for them; what they may <em>read</em> is {@link EntitlementPolicy}'s answer, applied
 * afterwards, and it is unchanged by any of this.
 *
 * <p>The owning instructor sees their own draft, which is the whole point of a draft, and their own
 * private course, which is the whole point of a private course.
 *
 * <h2>Private is enforced here, not in the client</h2>
 * A private course is withheld from every learner-facing path by this one method, because every one
 * of them resolves the course through it. Filtering cards in the browser would leave the ids, the
 * slugs, the lesson endpoints and the pricing endpoints all answering normally to anyone who typed
 * a URL — which is the whole of what "private" has to prevent.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseViewPolicy {

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;

    /**
     * The course, if this viewer may see it at all.
     *
     * @throws ResourceNotFoundException when they may not — the same answer a course that does not
     *         exist gets, so neither a draft nor a private course can be probed for by id. Not
     *         {@code 403}: telling somebody "this exists but is not for you" is itself the leak,
     *         since it confirms that a course with that id, and by extension that title in a
     *         guessed URL, is real.
     */
    public Course requireVisible(User user, Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        if (isVisibleTo(user, course)) {
            return course;
        }
        throw new ResourceNotFoundException("error.course.notFound", courseId.toString());
    }

    /**
     * The single {@code canViewCourse(user, course)} rule, in four clauses.
     *
     * <p>Each clause is a different reason to be let in, and they are deliberately independent:
     *
     * <ol>
     *   <li>the course is on offer to everyone — published <em>and</em> public;
     *   <li>it is the instructor's own;
     *   <li>the learner already holds it, whatever it has become since;
     *   <li>the viewer is staff, over a course that is published.
     * </ol>
     *
     * <p>Clause 3 is what makes going private safe. An enrolled learner is admitted by their
     * enrolment, never by the course's visibility, so an instructor switching a course to
     * {@code PRIVATE} cannot take it away from the hundred people already studying it — the same
     * guarantee that already held for unpublishing.
     */
    public boolean isVisibleTo(User user, Course course) {
        return course.isDiscoverable()
                || isOwningInstructor(user, course)
                || alreadyHolds(user, course.getId())
                || isStaffViewingPublishedCourse(user, course);
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

    /**
     * Staff keep over a private course exactly the reach they already had over a public one.
     *
     * <p>Deliberately scoped to a published course rather than being a blanket bypass. An
     * administrator could already open any published course — the first clause admitted them along
     * with everyone else — so without this, making a course private would quietly revoke an
     * existing staff capability. What it does not do is grant a new one: a draft is still the
     * instructor's own work in progress, invisible to staff exactly as it was before.
     */
    private boolean isStaffViewingPublishedCourse(User user, Course course) {
        return user != null
                && user.getRole() == Role.ADMIN
                && course.getStatus() == CourseStatus.PUBLISHED;
    }
}
