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
    SUBSCRIPTION_PLAN_RETIRED,

    /**
     * The Terms and Conditions version a registration accepted is not the one in force — either
     * superseded, or one this build has never published. One code for both, because the client's
     * remedy is the same: re-read the current terms and accept them. Nothing was created.
     */
    TERMS_VERSION_OUTDATED,

    /**
     * The server cannot say which Terms and Conditions version is current, so it will not take
     * consent. Answered {@code 503}: the caller did nothing wrong and retrying later is right.
     */
    TERMS_UNAVAILABLE,

    /**
     * The session was ended by something other than the person holding it — the account's password
     * was changed or reset from somewhere else, or the session predates a deploy that changed how
     * sessions are validated. It carries HTTP 401, and the session and its cookies are already gone
     * by the time the client reads it.
     *
     * <p>Distinct from an ordinary unauthenticated 401 on purpose, and this is the entire reason it
     * exists: without it a user whose session was revoked mid-action is dropped on the sign-in
     * screen with nothing to explain why. The client is expected to say "your session was ended,
     * please sign in again" for this code and stay silent for a plain 401, which is what a visitor
     * who was simply never signed in gets.
     *
     * <p>Reserved for exactly that condition. Reusing it for any other refusal would make the
     * message the client shows a lie.
     */
    SESSION_REVOKED
}
