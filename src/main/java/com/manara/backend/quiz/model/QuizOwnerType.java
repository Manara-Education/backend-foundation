package com.manara.backend.quiz.model;

/**
 * What a {@link Quiz} is attached to.
 *
 * <p>There is one quiz domain in this application. A lesson quiz, a module exam and a course final
 * exam are the same aggregate with a different owner — never separate entities, tables or services.
 */
public enum QuizOwnerType {

    /** Final exam of a course. */
    COURSE,

    /** Exam closing a module. */
    MODULE,

    /** Quiz attached to a single lesson. */
    LESSON
}
