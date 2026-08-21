package com.manara.backend.banner.model;

/**
 * How often one learner is shown a banner they have dismissed.
 *
 * <p>The three values differ only in how long a dismissal lasts, and that difference decides where
 * it is kept: {@link #ONCE_PER_STUDENT} has to outlive the browser, so it is a row in
 * {@code banner_dismissals}; the other two are deliberately scoped to a visit and stay on the
 * client, where a session already lives.
 */
public enum BannerDisplayFrequency {

    /** Dismissal lasts until the banner is next rendered — it comes back on the next visit. */
    EVERY_VISIT,

    /** Dismissal lasts for the browser session. */
    ONCE_PER_SESSION,

    /** Dismissed once and never shown to that learner again, on any device. Persisted server-side. */
    ONCE_PER_STUDENT
}
