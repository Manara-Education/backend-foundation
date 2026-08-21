package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Billing period unit of a course subscription plan.
 */
public enum SubscriptionUnit {

    DAY,
    WEEK,
    MONTH;

    @JsonCreator
    public static SubscriptionUnit fromJson(String value) {
        return EnumParser.parse(SubscriptionUnit.class, value);
    }
}
