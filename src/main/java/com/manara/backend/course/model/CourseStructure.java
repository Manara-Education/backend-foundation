package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Authoritative shape of a course's content tree.
 *
 * <p>{@link #FLAT} courses own their lessons directly; {@link #MODULES} courses own modules, and
 * every lesson lives under exactly one module. The two are never active at the same time — the
 * value stored here decides which side of the tree the API reads and writes.
 */
public enum CourseStructure {

    FLAT,
    MODULES;

    /**
     * Accepts the wire form in any case ({@code "modules"}, {@code "MODULES"}) while responses keep
     * the project's uppercase enum convention.
     */
    @JsonCreator
    public static CourseStructure fromJson(String value) {
        return EnumParser.parse(CourseStructure.class, value);
    }
}
