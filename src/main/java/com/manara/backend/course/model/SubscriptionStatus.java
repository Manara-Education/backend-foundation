package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Lifecycle of one purchased subscription term.
 *
 * <p>Stored rather than derived only from {@code expiresAt}: a term that was superseded by an early
 * renewal is closed on purpose, and that is not something a date comparison can say.
 */
public enum SubscriptionStatus {

    ACTIVE,
    EXPIRED;

    @JsonCreator
    public static SubscriptionStatus fromJson(String value) {
        return EnumParser.parse(SubscriptionStatus.class, value);
    }
}
