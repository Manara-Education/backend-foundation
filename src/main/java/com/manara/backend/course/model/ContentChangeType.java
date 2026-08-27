package com.manara.backend.course.model;

/**
 * What was done to a piece of course content.
 *
 * <p>Ordered weakest to strongest. One authoring request can touch the same entity several ways —
 * a lesson can be re-parented and retitled in a single save — and the change log records the
 * strongest of them rather than all of them, because the learner is being shown one sentence, not
 * an audit trail. {@link #ordinal()} is that precedence and the constants are ordered for it, so
 * "which of these two do I keep" is a comparison rather than a table of special cases.
 *
 * <p>Never exposed to a student as-is. {@code CourseChangeNarrator} turns the pair
 * ({@link ContentEntityType}, this) into a sentence in the learner's language.
 */
public enum ContentChangeType {

    /** Position within the same parent changed, and nothing else did. */
    REORDERED,

    /** Title, description or another label changed; the substance did not. */
    METADATA_UPDATED,

    /** The thing being learned from changed — video, body, attachments, questions, answers. */
    CONTENT_UPDATED,

    /** Moved to a different parent: a lesson that now sits under another module. */
    MOVED,

    /** No longer part of the course. The entity is gone, so this row is all that is left of it. */
    REMOVED,

    /**
     * Added to the course. Strongest deliberately: an entity created and then edited within the
     * same request is new to everyone who has not seen it, and calling it "updated" would be
     * describing a change to something they never had.
     */
    CREATED;

    /** The stronger of two descriptions of the same entity in one request. */
    public ContentChangeType or(ContentChangeType other) {
        return other == null || compareTo(other) >= 0 ? this : other;
    }
}
