package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Publication state of a course. Only {@link #PUBLISHED} courses are visible to learners.
 */
public enum CourseStatus {

    DRAFT,
    PUBLISHED;

    @JsonCreator
    public static CourseStatus fromJson(String value) {
        return EnumParser.parse(CourseStatus.class, value);
    }
}
