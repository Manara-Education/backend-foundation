package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rehearsal of the part of V5 no other test can reach: what it does to rows that already
 * exist.
 *
 * <p>The migration itself is proved to run by {@link FlywayMigrationTest}, but it runs against an
 * empty container — so it demonstrates the statements are valid SQL and nothing about what they do
 * to a production table whose {@code order_index} values are a mess. That table is the reason the
 * normalisation is in the migration at all: nothing before V5 stopped two modules of one course
 * from claiming the same position, so a real database can hold gaps, duplicates and a course whose
 * positions start at 7.
 *
 * <p>Legacy shapes are seeded and the normaliser is re-run over them inside a transaction that is
 * rolled back. That is possible precisely because the new constraint is
 * {@code DEFERRABLE INITIALLY DEFERRED}: duplicates can exist mid-transaction, which is what makes
 * a permutation writable in the first place, and is what lets this test set up the mess it is about.
 */
class CourseModuleOrderMigrationTest extends AbstractPostgresBackedTest {

    /** Copied verbatim from V5. If the migration's statement changes, this must too. */
    private static final String NORMALISE = """
            WITH normalised AS (SELECT id,
                                       ROW_NUMBER() OVER (
                                           PARTITION BY course_id
                                           ORDER BY order_index, id
                                           ) - 1 AS position
                                FROM public.course_modules)
            UPDATE public.course_modules m
            SET order_index = n.position
            FROM normalised n
            WHERE m.id = n.id
              AND m.order_index IS DISTINCT FROM n.position
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("gaps, duplicates and a run that does not start at zero all normalise to 0..N-1")
    void normalisesLegacyPositions() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Legacy positions");

            // The shapes a table with no uniqueness can genuinely hold.
            long first = seedModule(courseId, "First", 7);
            long second = seedModule(courseId, "Second", 7);
            long third = seedModule(courseId, "Third", 12);
            long fourth = seedModule(courseId, "Fourth", 40);

            jdbc.update(NORMALISE);

            assertThat(positionsOf(courseId))
                    .as("contiguous, zero-based, one position per module")
                    .containsExactly(0, 1, 2, 3);
            assertThat(titlesInOrder(courseId))
                    .as("the order these rows are already served in is preserved; the duplicate "
                            + "pair is settled by id, which is the only stable tiebreaker available")
                    .containsExactly("First", "Second", "Third", "Fourth");
            assertThat(List.of(first, second, third, fourth)).doesNotHaveDuplicates();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("each course is normalised on its own, never against another course's positions")
    void normalisesPerCourse() {
        transactionTemplate.executeWithoutResult(status -> {
            long left = seedCourse("Left");
            long right = seedCourse("Right");

            seedModule(left, "L1", 5);
            seedModule(left, "L2", 9);
            seedModule(right, "R1", 0);
            seedModule(right, "R2", 1);
            seedModule(right, "R3", 2);

            jdbc.update(NORMALISE);

            assertThat(positionsOf(left)).containsExactly(0, 1);
            assertThat(positionsOf(right)).containsExactly(0, 1, 2);
            assertThat(titlesInOrder(right))
                    .as("a course that was already correct must come out untouched")
                    .containsExactly("R1", "R2", "R3");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a course with no modules is left alone rather than failing on an empty aggregate")
    void anEmptyCourseIsFine() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("No modules");

            jdbc.update(NORMALISE);

            assertThat(positionsOf(courseId)).isEmpty();
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the back-fill leaves a published legacy course reading as 'not updated'")
    void theBackfillStartsLegacyCoursesQuiet() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Pre-existing");
            // As V4 left it: no publication baseline, no content version.
            jdbc.update("UPDATE courses SET last_published_at = NULL, content_updated_at = NULL "
                    + "WHERE id = ?", courseId);

            // The two statements V5 runs, verbatim in shape.
            jdbc.update("""
                    UPDATE public.courses
                    SET last_published_at  = COALESCE(updated_at, created_at),
                        content_updated_at = COALESCE(updated_at, created_at)
                    WHERE status = 'PUBLISHED'
                      AND last_published_at IS NULL
                      AND content_updated_at IS NULL
                    """);

            Boolean updated = jdbc.queryForObject(
                    "SELECT (status = 'PUBLISHED' AND last_published_at IS NOT NULL "
                            + "AND content_updated_at > last_published_at) FROM courses WHERE id = ?",
                    Boolean.class, courseId);

            assertThat(updated)
                    .as("every course that is live today must start with the badge off, or the "
                            + "deployment tells every learner every course just changed")
                    .isFalse();

            status.setRollbackOnly();
        });
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedCourse(String title) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, title + " instructor", "migration-" + title.hashCode() + "@x.test");

        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id)
                VALUES (?, 'seeded', 'PUBLISHED', 'MODULES', 'FREE', 0, now(), ?) RETURNING id
                """, Long.class, title, instructorId);
    }

    private long seedModule(long courseId, String title, int orderIndex) {
        return jdbc.queryForObject("""
                INSERT INTO course_modules (title, order_index, course_id, created_at)
                VALUES (?, ?, ?, now()) RETURNING id
                """, Long.class, title, orderIndex, courseId);
    }

    private List<Integer> positionsOf(long courseId) {
        return jdbc.query("SELECT order_index FROM course_modules WHERE course_id = ? ORDER BY order_index, id",
                (rs, row) -> rs.getInt(1), courseId);
    }

    private List<String> titlesInOrder(long courseId) {
        return jdbc.query("SELECT title FROM course_modules WHERE course_id = ? ORDER BY order_index, id",
                (rs, row) -> rs.getString(1), courseId);
    }
}
