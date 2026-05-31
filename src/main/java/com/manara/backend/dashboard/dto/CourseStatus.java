package com.manara.backend.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CourseStatus {
    IN_PROGRESS("in-progress"),
    COMPLETED("completed"),
    NOT_STARTED("not-started");

    private final String value;

    CourseStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
