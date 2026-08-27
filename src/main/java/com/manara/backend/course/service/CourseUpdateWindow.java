package com.manara.backend.course.service;

import com.manara.backend.course.dto.ContentChangeResponse;
import com.manara.backend.course.dto.ContentChangeState;
import com.manara.backend.course.dto.RemovedContentResponse;
import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.ContentEntityType;
import com.manara.backend.course.model.CourseChange;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.model.TrackedContent;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Everything one course changed, as it looks to one learner.
 *
 * <p>Built once per course-details request by {@link CourseUpdateResolver} and asked a question per
 * curriculum row. It holds the reader's own {@code enrolledAt} — resolved server-side from their
 * session, never from anything they sent — and the change log rows that fall after it.
 *
 * <h2>State comes from timestamps; wording comes from the log</h2>
 * These are two mechanisms, and it matters which decides what. {@link #stateOf} is arithmetic on
 * the entity's own {@code createdAt} and {@code contentUpdatedAt} — it needs no log row, works for
 * content that predates the log entirely, and cannot disagree with the course-level badge because
 * both read the same columns. The log only supplies the sentence. A missing log row therefore costs
 * a caption, never a wrong badge.
 *
 * <h2>Not enrolled</h2>
 * A visitor browsing the catalogue has no instant to measure against, so every row is
 * {@link ContentChangeState#UNCHANGED} and nothing is removed. "New to you" is meaningless for
 * someone who has not joined, and marking a shop window with update badges would be describing the
 * instructor's workflow rather than the reader's.
 */
public final class CourseUpdateWindow {

    private static final CourseUpdateWindow NOT_ENROLLED =
            new CourseUpdateWindow(null, Map.of(), List.of(), null);

    /** The reader's own enrollment, or {@code null} for somebody browsing the catalogue. */
    private final Enrollment enrollment;

    /** The most recent description of each entity, keyed by what it is and which one it is. */
    private final Map<EntityKey, CourseChange> latestByEntity;

    private final List<CourseChange> removals;
    private final CourseChangeNarrator narrator;

    CourseUpdateWindow(Enrollment enrollment, Map<EntityKey, CourseChange> latestByEntity,
                       List<CourseChange> removals, CourseChangeNarrator narrator) {
        this.enrollment = enrollment;
        this.latestByEntity = latestByEntity;
        this.removals = removals;
        this.narrator = narrator;
    }

    /** The view for somebody who has not joined this course. */
    public static CourseUpdateWindow notEnrolled() {
        return NOT_ENROLLED;
    }

    /**
     * The course-level badge, per enrollment.
     *
     * <p>Delegated to {@link Enrollment#hasCourseUpdates()} rather than re-derived, so this screen
     * and My Courses are reading one implementation of the rule and cannot come to different
     * conclusions about the same course.
     */
    public boolean hasUpdatesSinceEnrollment() {
        return enrollment != null && enrollment.hasCourseUpdates();
    }

    /** When the course last changed, or {@code null} for a viewer with no enrollment to measure from. */
    public LocalDateTime latestContentUpdateAt() {
        return enrollment == null ? null : enrollment.getCourse().getContentUpdatedAt();
    }

    /** Content that was in the course when this learner enrolled and is not in it now. */
    public List<RemovedContentResponse> removedContent() {
        return removals.stream()
                .map(change -> RemovedContentResponse.builder()
                        .entityType(change.getEntityType())
                        .title(change.getEntityTitle())
                        .summary(narrator.describe(change.getEntityType(), ContentChangeType.REMOVED, null, null))
                        .at(change.getOccurredAt())
                        .build())
                .toList();
    }

    /** @see #describe(TrackedContent, String) */
    public ContentChangeResponse describe(TrackedContent item) {
        return describe(item, null);
    }

    /**
     * What to say about one curriculum row.
     *
     * @param parentLabel the module this item sits under now, if any — needed only to complete
     *                    "moved from X to Y", and ignored for every other change
     */
    public ContentChangeResponse describe(TrackedContent item, String parentLabel) {
        if (item == null) {
            return null;
        }
        ContentChangeState state = stateOf(item);
        if (state == ContentChangeState.UNCHANGED) {
            return ContentChangeResponse.builder().state(state).build();
        }

        CourseChange change = latestByEntity.get(new EntityKey(item.contentType(), item.getId()));
        return ContentChangeResponse.builder()
                .state(state)
                .summary(summaryFor(state, item, change, parentLabel))
                .at(state == ContentChangeState.NEW ? item.getCreatedAt() : item.getContentUpdatedAt())
                .build();
    }

    /**
     * New, updated, or neither — decided against this learner's enrollment and nothing else.
     *
     * <p>The two branches cannot both be true. A created entity's {@code contentUpdatedAt} starts
     * equal to its {@code createdAt} and is never moved by the request that created it, so anything
     * newer than the enrollment by one measure is newer by both, and {@code NEW} — the more useful
     * of the two answers — is the one returned.
     *
     * <p>Strictly {@code isAfter}: a change landing in the same microsecond as an enrollment reads
     * as not-updated, because a learner who joined at that instant joined the changed version.
     */
    private ContentChangeState stateOf(TrackedContent item) {
        if (enrollment == null) {
            return ContentChangeState.UNCHANGED;
        }
        LocalDateTime enrolledAt = enrollment.getEnrolledAt();
        if (item.getCreatedAt() != null && item.getCreatedAt().isAfter(enrolledAt)) {
            return ContentChangeState.NEW;
        }
        if (item.getContentUpdatedAt() != null && item.getContentUpdatedAt().isAfter(enrolledAt)) {
            return ContentChangeState.UPDATED;
        }
        return ContentChangeState.UNCHANGED;
    }

    /**
     * Prefers the logged description, falls back to the state.
     *
     * <p>The fallback is what makes content that predates the change log — every course in the
     * database on the day this ships — still readable: the badge is correct from the timestamps and
     * the caption is the generic one for its kind.
     */
    private String summaryFor(ContentChangeState state, TrackedContent item, CourseChange change,
                              String parentLabel) {
        if (change != null) {
            String summary = narrator.describe(
                    change.getEntityType(), change.getChangeType(), change.getDetail(), parentLabel);
            if (summary != null) {
                return summary;
            }
        }
        ContentChangeType assumed = state == ContentChangeState.NEW
                ? ContentChangeType.CREATED
                : ContentChangeType.CONTENT_UPDATED;
        return narrator.describe(item.contentType(), assumed, null, null);
    }

    /** Identity of a change's subject: two entity types can hold the same id. */
    record EntityKey(ContentEntityType entityType, Long entityId) {
    }
}
