package com.manara.backend.course.model;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Who a course is offered to, which is a different question from whether it is finished.
 *
 * <h2>Not a publication state</h2>
 * A course has two independent axes and they answer two different questions:
 *
 * <pre>
 * status      DRAFT | PUBLISHED     — is this ready to be seen at all?
 * visibility  PUBLIC | PRIVATE      — and if it is, by whom?
 * </pre>
 *
 * <p>Every combination of the two is legal and means something. {@code DRAFT + PRIVATE} is an
 * unfinished course for a closed cohort; {@code PUBLISHED + PRIVATE} is a finished one that is
 * deliberately not on the catalogue. Folding {@code PRIVATE} into {@link CourseStatus} would make
 * those two indistinguishable and would make "make this private" destroy the fact that the course
 * was published — which is the baseline every learner's update badge is measured against.
 *
 * <p>{@link #PUBLIC} is the default everywhere: on the entity, in the column, and for every row
 * that existed before this enum did. A course cannot become invisible by not mentioning the field.
 *
 * @see Course#isDiscoverable()
 */
public enum CourseVisibility {

    /** On offer to everyone, subject to {@link CourseStatus} as it always has been. */
    PUBLIC,

    /**
     * Off the catalogue. Reachable only by the learners who already hold it, the owning instructor,
     * and staff — never by discovery, search, recommendation or a guessed id.
     */
    PRIVATE;

    /**
     * Accepts the wire form in any case ({@code "private"}, {@code "PRIVATE"}) while responses keep
     * the project's uppercase enum convention.
     */
    @JsonCreator
    public static CourseVisibility fromJson(String value) {
        return EnumParser.parse(CourseVisibility.class, value);
    }
}
