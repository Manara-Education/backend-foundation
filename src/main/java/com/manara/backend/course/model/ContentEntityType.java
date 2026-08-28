package com.manara.backend.course.model;

/**
 * What kind of thing inside a course a change happened to.
 *
 * <p>Student-facing vocabulary, not table names. {@link #QUIZ} and {@link #EXAM} are one entity in
 * the schema — a {@link com.manara.backend.quiz.model.Quiz} distinguished only by its owner — but
 * they are two different things to a learner, and the one place that difference has to survive is
 * the sentence they are shown. Translating it here, once, is what keeps every caller from having to
 * know that a module's "exam" and a lesson's "quiz" are the same row.
 */
public enum ContentEntityType {

    /** The course itself: its title, description, cover. */
    COURSE,

    MODULE,

    LESSON,

    /** A quiz attached to a single lesson. */
    QUIZ,

    /** A module exam or a course final exam. */
    EXAM
}
