package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Why a learner may open a course's content.
 *
 * <p>Mirrors {@link CourseAccessType}, but answers a different question: the access type is what the
 * instructor sells, the source is what this particular learner actually holds. They can disagree —
 * an instructor may switch a course to {@code SUBSCRIPTION} long after someone bought it outright,
 * and that purchase must keep working.
 */
public enum EntitlementSource {

    /** Granted by enrolling in a free course. Never expires. */
    FREE,

    /** Bought outright. Never expires. */
    PURCHASE,

    /** Bought for a fixed window under a {@link SubscriptionPlan}, and expires with it. */
    SUBSCRIPTION;

    @JsonCreator
    public static EntitlementSource fromJson(String value) {
        return EnumParser.parse(EntitlementSource.class, value);
    }
}
