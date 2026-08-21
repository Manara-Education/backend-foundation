package com.manara.backend.banner.model;

/**
 * What a banner is doing right now, derived from its own flags and window rather than stored.
 *
 * <p>Storing it would mean a scheduled banner needing something to wake up and promote it, and an
 * expired one needing something to retire it. Deriving it means a banner becomes active the moment
 * its start passes and stops the moment its end does, with nothing running in between.
 */
public enum BannerStatus {

    /** Saved but never published — invisible to learners whatever its window says. */
    DRAFT,

    /** Published and enabled, but its start is still ahead. */
    SCHEDULED,

    /** Published, enabled and inside its window. The only status a learner ever sees. */
    ACTIVE,

    /** Published and enabled, but its end has passed. */
    EXPIRED,

    /** Published and then switched off by its owner. */
    INACTIVE
}
