package com.manara.backend.course.service;

import com.manara.backend.course.model.AccessStatus;
import com.manara.backend.course.model.EntitlementSource;

import java.time.LocalDateTime;

/**
 * One learner's standing on one course, resolved by {@link EntitlementPolicy}.
 *
 * <p>Deliberately not a boolean. "Enrolled" and "may open the content" are different facts, and the
 * difference is the whole point of the expiry flow: a lapsed subscriber is still enrolled, still has
 * their progress, and still may not open a lesson.
 *
 * @param enrolled      they joined the course; survives expiry
 * @param entitled      they may be served protected content at the instant this was resolved
 * @param source        what the access rests on, or {@code null} when nothing was ever granted
 * @param expiresAt     {@code null} when the grant never ends
 * @param daysRemaining whole days until {@code expiresAt}, {@code null} when nothing expires
 * @param planId        the plan the current or most recent window was bought under
 */
public record CourseAccess(
        boolean enrolled,
        boolean entitled,
        EntitlementSource source,
        AccessStatus status,
        LocalDateTime startsAt,
        LocalDateTime expiresAt,
        Integer daysRemaining,
        Long planId) {

    /** Nobody the course tracks: a signed-out visitor, an instructor, a learner who never enrolled. */
    public static CourseAccess none() {
        return new CourseAccess(false, false, null, AccessStatus.NONE, null, null, null, null);
    }
}
