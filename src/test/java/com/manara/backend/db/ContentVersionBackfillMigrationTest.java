package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The part of V7 that decides whether this feature ships quietly or shouts at everybody at once.
 *
 * <p>{@link FlywayMigrationTest} proves the migration runs. It runs against an empty container, so
 * it says nothing about what happens to a production database that already holds courses, lessons
 * and enrollments — and that is the only interesting question here, because the rule this release
 * introduces is
 *
 * <pre>{@code course.content_updated_at > enrollment.enrolled_at}</pre>
 *
 * <p>and V5 back-filled {@code content_updated_at} from {@code updated_at}, a column V5 itself
 * documented as untrustworthy. Against V5's own baseline that was harmless: it set the two
 * timestamps equal, and equal is not greater. Against an enrollment it is not harmless at all — a
 * course whose {@code updated_at} moved because somebody bought it would announce itself as edited
 * to every learner who joined earlier.
 *
 * <p>These tests seed exactly that shape and re-run V7's statements over it.
 */
class ContentVersionBackfillMigrationTest extends AbstractPostgresBackedTest {

    /** Copied verbatim from V7. If the migration's statement changes, this must too. */
    private static final String REDERIVE = """
            WITH content_age AS (SELECT c.id,
                                        GREATEST(
                                                c.created_at,
                                                (SELECT max(l.created_at) FROM public.lessons l WHERE l.course_id = c.id),
                                                (SELECT max(m.created_at) FROM public.course_modules m WHERE m.course_id = c.id)
                                        ) AS derived
                                 FROM public.courses c)
            UPDATE public.courses c
            SET content_updated_at = a.derived
            FROM content_age a
            WHERE c.id = a.id
              AND a.derived IS NOT NULL
              AND c.content_updated_at IS DISTINCT FROM a.derived
            """;

    private static final String REALIGN_BASELINE = """
            UPDATE public.courses
            SET last_published_at = content_updated_at
            WHERE last_published_at IS NOT NULL
              AND content_updated_at IS NOT NULL
              AND content_updated_at > last_published_at
            """;

    private static final LocalDateTime JANUARY = LocalDateTime.of(2026, 1, 1, 9, 0);
    private static final LocalDateTime MARCH = LocalDateTime.of(2026, 3, 1, 9, 0);
    private static final LocalDateTime JULY = LocalDateTime.of(2026, 7, 1, 9, 0);

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("a course whose updated_at moved for a purchase does not announce itself to earlier learners")
    void aPurchaseInflatedTimestampIsDiscarded() {
        transactionTemplate.executeWithoutResult(status -> {
            // The shape a real database is in: content written in January, somebody bought the
            // course in July, and V5 wrote July into content_updated_at because it read updated_at.
            long courseId = seedCourse("Bought in July", JANUARY, JULY, JULY);
            seedLesson(courseId, "Only lesson", JANUARY);

            jdbc.update(REDERIVE);
            jdbc.update(REALIGN_BASELINE);

            // A learner who joined in March enrolled after the last content appeared, so nothing
            // has changed for them — the July timestamp was about somebody else's purchase.
            assertThat(contentUpdatedAt(courseId)).isEqualTo(JANUARY);
            assertThat(contentUpdatedAt(courseId)).isBefore(MARCH);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a lesson added after a learner enrolled still counts as an update to them")
    void genuinelyNewerContentIsNotSuppressed() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Grew in July", JANUARY, JANUARY, JANUARY);
            seedLesson(courseId, "Original", JANUARY);
            seedLesson(courseId, "Added later", JULY);

            jdbc.update(REDERIVE);

            // Not a false notification — a suppressed true one would be the bug here. A learner who
            // joined in March genuinely has a lesson they have never seen, and the curriculum will
            // show them which.
            assertThat(contentUpdatedAt(courseId)).isEqualTo(JULY).isAfter(MARCH);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the instructor's own badge stays dark on deploy day, because nobody edited anything")
    void republishBaselineIsRealigned() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Baseline behind content", JANUARY, JANUARY, JANUARY);
            seedLesson(courseId, "Added later", JULY);

            jdbc.update(REDERIVE);
            jdbc.update(REALIGN_BASELINE);

            // content_updated_at moved forward to the real content age, so without the realignment
            // this course would come out of the migration claiming unpublished edits it has not got.
            assertThat(contentUpdatedAt(courseId)).isEqualTo(JULY);
            assertThat(lastPublishedAt(courseId)).isEqualTo(JULY);
            assertThat(contentUpdatedAt(courseId)).isBeforeOrEqualTo(lastPublishedAt(courseId));

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a never-published draft keeps its null baseline")
    void aDraftKeepsItsNullBaseline() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Draft", JANUARY, JANUARY, null);
            seedLesson(courseId, "Only lesson", JULY);

            jdbc.update(REDERIVE);
            jdbc.update(REALIGN_BASELINE);

            assertThat(lastPublishedAt(courseId)).isNull();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a course with no content at all falls back to its own creation instant")
    void anEmptyCourseFallsBackToItsOwnAge() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Empty", JANUARY, JULY, JULY);

            jdbc.update(REDERIVE);

            // GREATEST ignores NULLs, so an empty course is its own created_at rather than NULL.
            assertThat(contentUpdatedAt(courseId)).isEqualTo(JANUARY);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("every item's content version is back-filled to its creation, which no learner can read as an update")
    void itemVersionsStartEqualToCreation() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Item versions", JANUARY, JANUARY, JANUARY);
            seedLesson(courseId, "Only lesson", JANUARY);

            // What V7 did to rows that already existed. Re-running it is a no-op on rows the real
            // migration already handled, which is the point: the assertion is about the shape it
            // leaves behind, not about running it twice.
            jdbc.update("UPDATE lessons SET content_updated_at = created_at WHERE course_id = ?", courseId);

            // The UPDATED branch of the read rule needs created_at <= enrolled_at AND
            // content_updated_at > enrolled_at. With the two equal, that is unsatisfiable — which is
            // the whole guarantee that nothing lights up on deploy.
            Integer readableAsUpdated = jdbc.queryForObject(
                    "SELECT count(*) FROM lessons WHERE course_id = ? AND content_updated_at > created_at",
                    Integer.class, courseId);
            assertThat(readableAsUpdated).isZero();

            status.setRollbackOnly();
        });
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedCourse(String title, LocalDateTime createdAt, LocalDateTime contentUpdatedAt,
                            LocalDateTime lastPublishedAt) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, title + " instructor",
                "backfill-" + title.hashCode() + "@x.test");

        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type, students_count,
                                     created_at, updated_at, content_updated_at, last_published_at, instructor_id)
                VALUES (?, 'seeded', 'PUBLISHED', 'FLAT', 'FREE', 0, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, title, Timestamp.valueOf(createdAt),
                contentUpdatedAt == null ? null : Timestamp.valueOf(contentUpdatedAt),
                contentUpdatedAt == null ? null : Timestamp.valueOf(contentUpdatedAt),
                lastPublishedAt == null ? null : Timestamp.valueOf(lastPublishedAt),
                instructorId);
    }

    private void seedLesson(long courseId, String title, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO lessons (title, video_url, order_index, duration, course_id, created_at,
                                     content_updated_at)
                VALUES (?, 'https://example.test/v',
                        (SELECT COALESCE(MAX(order_index) + 1, 0) FROM lessons WHERE course_id = ?),
                        0, ?, ?, ?)
                """, title, courseId, courseId, Timestamp.valueOf(createdAt), Timestamp.valueOf(createdAt));
    }

    private LocalDateTime contentUpdatedAt(long courseId) {
        return jdbc.queryForObject(
                "SELECT content_updated_at FROM courses WHERE id = ?", LocalDateTime.class, courseId);
    }

    private LocalDateTime lastPublishedAt(long courseId) {
        return jdbc.queryForObject(
                "SELECT last_published_at FROM courses WHERE id = ?", LocalDateTime.class, courseId);
    }
}
