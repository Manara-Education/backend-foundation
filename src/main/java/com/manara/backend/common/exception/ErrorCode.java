package com.manara.backend.common.exception;

/**
 * The machine-readable name of a business condition the API refuses.
 *
 * <p>Separate from the message, and for a different audience. The message is localized prose for
 * whoever is looking at the screen; this is a stable identifier the client branches on — "show the
 * reload prompt" rather than "match on an Arabic string". Adding one is how a condition stops being
 * an anonymous 4xx.
 *
 * <p>Every code here replaces something the database used to reject on the application's behalf. A
 * duplicate lesson position and a subscription plan a learner still holds were both surfacing as
 * the same generic {@code 409 "The request conflicts with data that already exists"}, which told
 * the instructor nothing and told the client nothing it could act on. Business conditions are now
 * decided before the write, in the domain, and named here.
 */
public enum ErrorCode {

    /**
     * The aggregate save was built on a revision of the course that is no longer current — another
     * tab or session has saved since. Nothing was written.
     */
    COURSE_VERSION_CONFLICT,

    /** An aggregate save arrived without the revision it was built from, so it cannot be checked. */
    COURSE_REVISION_REQUIRED,

    /** A lesson was asked to be placed outside its sibling scope's valid range. */
    INVALID_LESSON_POSITION,

    /** The subscription plan is no longer offered; existing subscribers keep their term. */
    SUBSCRIPTION_PLAN_RETIRED
}
