package com.manara.backend.course.model;

import java.time.LocalDateTime;

/**
 * A piece of a course a learner can be told has changed.
 *
 * <p>Implemented by {@link Course}, {@link CourseModule},
 * {@link com.manara.backend.lesson.model.Lesson} and
 * {@link com.manara.backend.quiz.model.Quiz} — the four things the curriculum screen draws a row
 * for. It exists so that recording a change and stamping one is written once rather than four
 * times: {@code CourseContentChanges} accepts any of them, and the single stamping pass at the end
 * of an authoring transaction walks them without knowing which is which.
 *
 * <h2>Two timestamps, and why neither is {@code updatedAt}</h2>
 * {@link #getCreatedAt()} answers "did this exist when the learner enrolled". It is
 * {@code updatable = false} on every implementation, so it is safe to compare against an
 * enrollment.
 *
 * <p>{@link #getContentUpdatedAt()} answers "has it changed since". Deliberately not Hibernate's
 * {@code @PreUpdate} stamp, which moves for reasons no learner should hear about — a background
 * video lookup rewriting {@code lessons.duration}, a purchase incrementing
 * {@code courses.students_count}. Only {@link #markContentChanged(LocalDateTime)} moves it, and
 * only the authoring services call that.
 *
 * <h2>Identity</h2>
 * {@link #getId()} may be {@code null} while an entity is being created, so nothing keys a
 * collection on it. It is read at the end of the transaction, after the flush that assigns it.
 */
public interface TrackedContent {

    Long getId();

    /** How a learner should hear about this thing — not which table it lives in. */
    ContentEntityType contentType();

    /**
     * The name to show for it, captured now.
     *
     * <p>Snapshotted into the change log rather than joined to at read time, because the one case
     * the log has to describe on its own is content that no longer exists.
     */
    String contentTitle();

    LocalDateTime getCreatedAt();

    LocalDateTime getContentUpdatedAt();

    /** @see Course#markContentChanged(LocalDateTime) for the full contract. */
    void markContentChanged(LocalDateTime at);
}
