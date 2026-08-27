package com.manara.backend.course.dto;

/**
 * What one piece of a course is, relative to the learner reading it.
 *
 * <p>Relative is the whole of it. The same lesson is {@link #NEW} to somebody who enrolled last
 * month and {@link #UNCHANGED} to somebody who enrolled this morning, because they bought different
 * versions of the same course. There is no global "this lesson is new" and there deliberately
 * cannot be one.
 */
public enum ContentChangeState {

    /** Did not exist when this learner enrolled. */
    NEW,

    /** Existed when they enrolled, and has changed since. */
    UPDATED,

    /**
     * Nothing to say about it — either it has not changed since they enrolled, or they are not
     * enrolled at all and there is no instant to measure against.
     */
    UNCHANGED
}
