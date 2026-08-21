package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * How a learner gains access to a course.
 *
 * <p>This replaces the implicit "price {@code > 0} means paid" rule the checkout flow used to
 * apply. Legacy rows are migrated by inferring {@link #PURCHASE} for a positive price and
 * {@link #FREE} otherwise.
 */
public enum CourseAccessType {

    FREE,
    PURCHASE,
    SUBSCRIPTION;

    @JsonCreator
    public static CourseAccessType fromJson(String value) {
        return EnumParser.parse(CourseAccessType.class, value);
    }
}
