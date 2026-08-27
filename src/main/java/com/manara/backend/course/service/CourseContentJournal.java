package com.manara.backend.course.service;

import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseChange;
import com.manara.backend.course.model.TrackedContent;
import com.manara.backend.course.repository.CourseChangeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Commits what an authoring request changed — to the entities, and to the log.
 *
 * <p>The single place either happens. Scattering {@code markContentChanged} through the services is
 * how a course ends up claiming to be updated by a request that rolled back, or failing to say so
 * for one that committed; doing it once, at the end, inside the caller's transaction, means the
 * timestamps and the change they describe can only commit or roll back together.
 *
 * <h2>One instant</h2>
 * The caller passes the transaction's own instant rather than this class reading a clock. Every
 * entity touched by one request therefore carries the same {@code content_updated_at} as the course
 * and as its log rows — so "everything that changed since I enrolled" cannot return a course marked
 * updated whose changes all sort a microsecond before the badge that announced them.
 *
 * <h2>What is not stamped</h2>
 * <ul>
 *   <li>{@link ContentChangeType#CREATED} — a new entity's {@code contentUpdatedAt} already equals
 *       its {@code createdAt}, and leaving it there is what keeps {@code NEW} and {@code UPDATED}
 *       mutually exclusive rather than a rule about which to check first.
 *   <li>{@link ContentChangeType#REMOVED} — there is nothing left to stamp. Its id and title are
 *       read here, before the delete, and the log row is the only thing that outlives it.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseContentJournal {

    private final CourseChangeRepository courseChangeRepository;

    /**
     * Records everything one request changed, or does nothing at all.
     *
     * <p>A save that turns out to be a no-op leaves every previous value alone, which is what stops
     * re-submitting a course unchanged from announcing a new version to its learners.
     *
     * @return whether anything was recorded
     */
    public boolean commit(Course course, CourseContentChanges changes, LocalDateTime at) {
        if (!changes.hasChanges()) {
            return false;
        }

        course.markContentChanged(at);

        // A course that has never been published has never had a learner — the unique constraint on
        // enrollments is reached through a published-course check on every checkout path — so no
        // reader can ever exist for these rows. Stamping without logging keeps an instructor's
        // iteration on a draft out of the table entirely, and the timestamps still say everything
        // the first cohort needs to know once it goes live.
        if (course.getLastPublishedAt() == null) {
            log.debug("Course content changed before first publication, not journalled: courseId={}",
                    course.getId());
            return true;
        }

        List<CourseChange> rows = new ArrayList<>();
        for (CourseContentChanges.Entry entry : changes.entries()) {
            TrackedContent target = entry.target();
            if (stampable(entry.type())) {
                target.markContentChanged(at);
            }
            // The course's own row is written from `course`, so an entry that *is* the course does
            // not produce a second one under a null entity id.
            rows.add(rowFor(course, entry, at));
        }
        courseChangeRepository.saveAll(rows);

        log.info("Course content changed: courseId={} at={} entries={}", course.getId(), at, rows.size());
        return true;
    }

    private boolean stampable(ContentChangeType type) {
        return type != ContentChangeType.CREATED && type != ContentChangeType.REMOVED;
    }

    /**
     * Ids are read here rather than when the change was recorded, because a created entity only has
     * one after the flush — and by the time this runs, every authoring path has flushed.
     */
    private CourseChange rowFor(Course course, CourseContentChanges.Entry entry, LocalDateTime at) {
        TrackedContent target = entry.target();
        boolean isCourseItself = target == course;

        return CourseChange.builder()
                .courseId(course.getId())
                .entityType(target.contentType())
                .entityId(isCourseItself ? null : target.getId())
                .entityTitle(truncate(target.contentTitle()))
                .changeType(entry.type())
                .detail(truncate(entry.detail()))
                .occurredAt(at)
                .build();
    }

    /**
     * A title longer than the column is a snapshot problem, not a reason to fail an instructor's
     * save. The column is 255; titles are validated far shorter than that, so this is a backstop
     * for data that predates the validation rather than something the editor can reach.
     */
    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
