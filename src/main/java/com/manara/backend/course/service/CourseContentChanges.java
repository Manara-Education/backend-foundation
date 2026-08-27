package com.manara.backend.course.service;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Whether one authoring request actually changed anything a learner can see.
 *
 * <p>This exists so the "Updated" badge cannot lie in either direction. A fragile alternative —
 * stamping {@code contentUpdatedAt} at the top of every mutation — would mark a course updated for
 * a save that changed nothing, which is exactly what the editor does every time an instructor
 * opens a lesson form and closes it again. Asking Hibernate afterwards would be the other extreme:
 * its dirty set also contains {@code duration}, which a background video lookup rewrites, and
 * {@code studentsCount}, which a learner's purchase increments.
 *
 * <p>So the authoring path says so explicitly, through {@link #set}, and the timestamp is written
 * once at the end of the transaction by whoever owns the course — never scattered through
 * controllers or mappers.
 *
 * <p>Not thread-safe, and deliberately so: one instance belongs to one request.
 */
public final class CourseContentChanges {

    private boolean changed;

    /**
     * Assigns {@code newValue} only when it differs from what is there, and remembers that it did.
     *
     * <p>Comparing before assigning is what keeps a no-op save a no-op: re-submitting a course
     * with its own title neither writes a row nor moves the content version.
     */
    public <T> void set(T currentValue, T newValue, Consumer<T> setter) {
        if (!Objects.equals(currentValue, newValue)) {
            setter.accept(newValue);
            changed = true;
        }
    }

    /** Records a change that is not a field assignment — a created or deleted child, a reorder. */
    public void record() {
        changed = true;
    }

    /** Records a change only if {@code condition} holds. */
    public void recordIf(boolean condition) {
        if (condition) {
            changed = true;
        }
    }

    public boolean hasChanges() {
        return changed;
    }
}
