package com.manara.backend.course.service;

import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.TrackedContent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What one authoring request actually changed, and to what.
 *
 * <p>This exists so the "Updated" badge cannot lie in either direction. A fragile alternative —
 * stamping {@code contentUpdatedAt} at the top of every mutation — would mark a course updated for
 * a save that changed nothing, which is exactly what the editor does every time an instructor
 * opens a lesson form and closes it again. Asking Hibernate afterwards would be the other extreme:
 * its dirty set also contains {@code duration}, which a background video lookup rewrites, and
 * {@code studentsCount}, which a learner's purchase increments.
 *
 * <h2>Every change names its subject</h2>
 * There is deliberately no way to record a change without saying what changed. The previous version
 * of this class carried a single boolean, which was enough to answer "did this course change?" and
 * nothing else — so the curriculum could only ever mark every row updated or none. Recording per
 * entity costs the caller one word ({@code changes.of(lesson)}) and is what lets one edited lesson
 * be the only thing a learner is pointed at.
 *
 * <h2>Pricing is not here at all</h2>
 * Not by convention — structurally. A price, a discount, an access type and a subscription plan are
 * commerce, not curriculum, and none of them implements {@link TrackedContent}, so none of them can
 * reach this class even by accident. That is the whole of the guarantee that repricing a course
 * does not tell everyone enrolled that it changed.
 *
 * <h2>One entity, one sentence</h2>
 * A single request can touch the same lesson several ways — retitle it, re-point its video and move
 * it to another module. Each is recorded, and the strongest wins
 * ({@link ContentChangeType#or(ContentChangeType)}), because the learner is shown one sentence
 * rather than a changelog.
 *
 * <p>Not thread-safe, and deliberately so: one instance belongs to one request.
 */
public final class CourseContentChanges {

    /**
     * Keyed by object identity, not by {@code equals}.
     *
     * <p>Two reasons, both load-bearing. A freshly created entity has a null id, so its
     * {@code hashCode} is 0 and its {@code equals} is false against everything — several new lessons
     * in one request would collapse or scatter unpredictably in a hash map. And an id assigned by
     * the flush half way through a pass would change an already-inserted key's hash, losing the
     * entry. Within one persistence context JPA guarantees one instance per row, so identity is
     * exactly the right notion here.
     */
    private final Map<TrackedContent, Entry> index = new IdentityHashMap<>();

    /** Insertion order, so the change log reads in the order the instructor's edit was applied. */
    private final List<Entry> ordered = new ArrayList<>();

    /**
     * Whether this request changed something real about the course that its learners are not told
     * about — what it costs, or whether it is on the catalogue.
     *
     * <p>A deliberately separate channel, and it can do exactly one thing: move the course's
     * revision. It cannot stamp {@code contentUpdatedAt}, it cannot write a change-log row, and it
     * cannot reach a {@link TrackedContent} — so recording one still cannot tell a learner their
     * course changed because somebody else's price did. What it does have to do is make the stored
     * aggregate differ from the copy an open tab is holding: a repricing another tab never saw is
     * precisely the edit a stale full-replacement save would put back.
     */
    private boolean unannouncedChange;

    /** Opens a recording scope for one piece of content. */
    public Scope of(TrackedContent target) {
        return new Scope(this, target);
    }

    /**
     * Records a real change to the aggregate that its learners are deliberately not told about.
     *
     * <p>Pricing, access type, subscription plans and publication state. Nothing here reaches the
     * badge or the change log — see the field it sets.
     */
    public void recordUnannouncedChange() {
        this.unannouncedChange = true;
    }

    public boolean hasChanges() {
        return !ordered.isEmpty();
    }

    /** Whether anything at all about the stored aggregate changed, announced to learners or not. */
    public boolean hasAggregateChanges() {
        return unannouncedChange || hasChanges();
    }

    /** Everything this request changed, strongest description per entity, in the order applied. */
    public List<Entry> entries() {
        return List.copyOf(ordered);
    }

    private void record(TrackedContent target, ContentChangeType type, String detail) {
        Entry existing = index.get(target);
        if (existing == null) {
            Entry entry = new Entry(target, type, detail);
            index.put(target, entry);
            ordered.add(entry);
            return;
        }
        // The stronger description wins, and brings its own detail with it — a lesson that is moved
        // and then retitled is "moved", and the module it came from is the fact that sentence needs.
        ContentChangeType strongest = type.or(existing.type);
        if (strongest != existing.type) {
            existing.type = strongest;
            existing.detail = detail;
        }
    }

    /**
     * One piece of content, and the ways it can be said to have changed.
     *
     * <p>The vocabulary is the point. A caller has to choose between {@code metadata} and
     * {@code content} — between renaming a lesson and replacing its video — which is a distinction
     * the learner cares about and one that is free to make at the call site and impossible to
     * recover afterwards.
     */
    public static final class Scope {

        private final CourseContentChanges changes;
        private final TrackedContent target;

        private Scope(CourseContentChanges changes, TrackedContent target) {
            this.changes = changes;
            this.target = target;
        }

        /**
         * Assigns {@code next} only when it differs from what is there, and records a label change.
         *
         * <p>Comparing before assigning is what keeps a no-op save a no-op: re-submitting a lesson
         * with its own title neither writes a row nor moves the content version.
         */
        public <T> Scope metadata(T current, T next, Consumer<T> setter) {
            return assign(ContentChangeType.METADATA_UPDATED, current, next, setter);
        }

        /** As {@link #metadata}, for a change to the thing actually being learned from. */
        public <T> Scope content(T current, T next, Consumer<T> setter) {
            return assign(ContentChangeType.CONTENT_UPDATED, current, next, setter);
        }

        /** As {@link #metadata}, for a change of position among unchanged siblings. */
        public <T> Scope reordered(T current, T next, Consumer<T> setter) {
            return assign(ContentChangeType.REORDERED, current, next, setter);
        }

        /** Added to the course by this request. */
        public Scope created() {
            changes.record(target, ContentChangeType.CREATED, null);
            return this;
        }

        /**
         * Re-parented by this request.
         *
         * @param fromLabel the parent it left, captured before the write — the one fact the
         *                  resulting sentence needs and the only one that is gone afterwards
         */
        public Scope moved(String fromLabel) {
            changes.record(target, ContentChangeType.MOVED, fromLabel);
            return this;
        }

        /**
         * Dropped from the course by this request.
         *
         * <p>Recorded against an entity that is about to stop existing, which is why the journal
         * reads its id and title now and never stamps it.
         */
        public Scope removed() {
            changes.record(target, ContentChangeType.REMOVED, null);
            return this;
        }

        /** For a change already applied elsewhere — a nested aggregate that reports its own diff. */
        public Scope recordIf(boolean condition, ContentChangeType type) {
            if (condition) {
                changes.record(target, type, null);
            }
            return this;
        }

        private <T> Scope assign(ContentChangeType type, T current, T next, Consumer<T> setter) {
            if (!Objects.equals(current, next)) {
                setter.accept(next);
                changes.record(target, type, null);
            }
            return this;
        }
    }

    /** One entity, and the strongest thing this request did to it. */
    public static final class Entry {

        private final TrackedContent target;
        private ContentChangeType type;
        private String detail;

        private Entry(TrackedContent target, ContentChangeType type, String detail) {
            this.target = target;
            this.type = type;
            this.detail = detail;
        }

        public TrackedContent target() {
            return target;
        }

        public ContentChangeType type() {
            return type;
        }

        public String detail() {
            return detail;
        }
    }
}
