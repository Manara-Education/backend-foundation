package com.manara.backend.course.model;

/**
 * What the course screen should say about a learner's standing, decided here rather than in a
 * client.
 *
 * <p>{@link #EXPIRING_SOON} in particular is a server decision: the threshold is a product rule, and
 * a client computing it from a date would let two screens disagree about the same subscription.
 */
public enum AccessStatus {

    /** Nothing has ever been granted for this course. */
    NONE,

    /** Access is open, and either never expires or expires further out than the warning window. */
    ACTIVE,

    /** Access is open but the subscription window closes shortly. */
    EXPIRING_SOON,

    /** A subscription window closed. The enrolment and everything learned under it survive. */
    EXPIRED
}
