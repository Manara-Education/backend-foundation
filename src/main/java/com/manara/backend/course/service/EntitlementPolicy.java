package com.manara.backend.course.service;

import com.manara.backend.course.model.AccessStatus;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The single answer to "may this learner open this course".
 *
 * <p>Access used to be the presence of an {@link com.manara.backend.course.model.Enrollment}, checked
 * separately by every path that needed it. That worked while every enrolment was permanent; it has no
 * way to express a subscription running out. Now one {@link CourseEntitlement} row per learner per
 * course carries the whole answer, and every protected read — course details, the lesson list, a
 * lesson's video, a quiz submission — is decided here.
 *
 * <p>Three shapes of valid access, and the caller never needs to know which it got:
 *
 * <ul>
 *   <li>a {@link EntitlementSource#FREE} grant, from enrolling in a free course — perpetual;</li>
 *   <li>a {@link EntitlementSource#PURCHASE} grant, from buying a course outright — perpetual;</li>
 *   <li>a {@link EntitlementSource#SUBSCRIPTION} grant, open until its window closes.</li>
 * </ul>
 *
 * <p>Expiry is never destructive. The entitlement row stays, so does the enrolment, and so does every
 * completed lesson and quiz attempt — renewing puts the learner back exactly where they stopped.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EntitlementPolicy {

    /** How close to the end a subscription starts warning. A product rule, decided server-side. */
    static final int EXPIRING_SOON_DAYS = 7;

    private final CourseEntitlementRepository courseEntitlementRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final StudentRepository studentRepository;
    private final Clock clock;

    /** For write paths that already hold the learner. */
    public boolean isEntitled(Long courseId, Long studentId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return courseEntitlementRepository.findByCourseIdAndStudentId(courseId, studentId)
                .filter(entitlement -> entitlement.isActiveAt(now))
                .isPresent();
    }

    public CourseAccess accessOf(Long courseId, Student student) {
        if (student == null) {
            return CourseAccess.none();
        }

        boolean enrolled = enrollmentRepository.existsByCourseIdAndStudentId(courseId, student.getId());
        Optional<CourseEntitlement> entitlement =
                courseEntitlementRepository.findByCourseIdAndStudentId(courseId, student.getId());

        return entitlement
                .map(granted -> describe(enrolled, granted))
                .orElseGet(() -> new CourseAccess(
                        enrolled, false, null, AccessStatus.NONE, null, null, null, null));
    }

    /**
     * For read paths that start from the authenticated principal. A viewer who is not a learner —
     * signed out, or an instructor — has no standing to describe, which is not the same as being
     * refused: what they may see is decided by {@link LearnerCourseAccess}.
     */
    public CourseAccess accessOf(User user, Long courseId) {
        if (user == null || user.getRole() != Role.STUDENT) {
            return CourseAccess.none();
        }
        return studentRepository.findByUserId(user.getId())
                .map(student -> accessOf(courseId, student))
                .orElseGet(CourseAccess::none);
    }

    private CourseAccess describe(boolean enrolled, CourseEntitlement entitlement) {
        LocalDateTime now = LocalDateTime.now(clock);
        boolean active = entitlement.isActiveAt(now);
        LocalDateTime expiresAt = entitlement.getExpiresAt();

        Integer daysRemaining = expiresAt == null || !active
                ? null
                : (int) Duration.between(now, expiresAt).toDays();

        return new CourseAccess(
                enrolled,
                active,
                entitlement.getSource(),
                statusOf(active, daysRemaining),
                entitlement.getStartsAt(),
                expiresAt,
                daysRemaining,
                entitlement.getSubscriptionPlan() == null ? null : entitlement.getSubscriptionPlan().getId());
    }

    private AccessStatus statusOf(boolean active, Integer daysRemaining) {
        if (!active) {
            return AccessStatus.EXPIRED;
        }
        // Perpetual access has no days remaining and can never be "expiring soon".
        if (daysRemaining != null && daysRemaining < EXPIRING_SOON_DAYS) {
            return AccessStatus.EXPIRING_SOON;
        }
        return AccessStatus.ACTIVE;
    }
}
