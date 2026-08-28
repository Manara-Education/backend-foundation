package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A rehearsal of the part of V6 no other test can reach: what it does to rows that already exist.
 *
 * <p>{@link FlywayMigrationTest} proves the migration runs, but it runs against an empty container,
 * so it says nothing about a production {@code lessons} table. And that table is very likely to be
 * a mess: nothing before V6 stopped two lessons of one module from claiming the same position, and
 * until this release the aggregate save wrote positions from the submitted array while a lesson
 * moved between modules carried its old position into its new parent.
 *
 * <p>The scope is the pair {@code (course_id, module_id)}, not the course — two modules of one
 * course each having a lesson at position 0 is correct and must survive normalisation untouched.
 * Flat courses, whose lessons all have {@code module_id IS NULL}, are one scope of their own.
 *
 * <p>Legacy shapes are seeded and the normaliser re-run over them inside a transaction that is
 * rolled back. That works precisely because the new constraint is {@code DEFERRABLE INITIALLY
 * DEFERRED}: duplicates can exist mid-transaction, which is what makes a permutation writable in
 * the first place, and is what lets this test set up the mess it is about.
 */
class LessonOrderMigrationTest extends AbstractPostgresBackedTest {

    /** Copied verbatim from V6. If the migration's statement changes, this must too. */
    private static final String NORMALISE = """
            WITH normalised AS (SELECT id,
                                       ROW_NUMBER() OVER (
                                           PARTITION BY course_id, module_id
                                           ORDER BY order_index, id
                                           ) - 1 AS position
                                FROM public.lessons)
            UPDATE public.lessons l
            SET order_index = n.position
            FROM normalised n
            WHERE l.id = n.id
              AND l.order_index IS DISTINCT FROM n.position
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("gaps, duplicates and a run that does not start at zero all normalise to 0..N-1")
    void normalisesLegacyPositions() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Legacy lesson positions");
            long moduleId = seedModule(courseId, "Only module");

            seedLesson(courseId, moduleId, "First", 4);
            seedLesson(courseId, moduleId, "Second", 4);
            seedLesson(courseId, moduleId, "Third", 11);

            jdbc.update(NORMALISE);

            assertThat(positionsOf(courseId, moduleId)).containsExactly(0, 1, 2);
            assertThat(titlesOf(courseId, moduleId))
                    .as("the order these rows are already served in is preserved; the duplicate "
                            + "pair is settled by id, the only stable tiebreaker available")
                    .containsExactly("First", "Second", "Third");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("two modules of one course keep their own independent runs")
    void normalisesPerModuleNotPerCourse() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Two modules");
            long first = seedModule(courseId, "First");
            long second = seedModule(courseId, "Second");

            seedLesson(courseId, first, "A1", 0);
            seedLesson(courseId, first, "A2", 1);
            seedLesson(courseId, second, "B1", 0);
            seedLesson(courseId, second, "B2", 3);

            jdbc.update(NORMALISE);

            assertThat(positionsOf(courseId, first))
                    .as("a scope that was already correct must come out untouched")
                    .containsExactly(0, 1);
            assertThat(positionsOf(courseId, second)).containsExactly(0, 1);
            assertThat(titlesOf(courseId, second)).containsExactly("B1", "B2");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a flat course's root lessons are one scope, normalised on their own")
    void normalisesRootLessons() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Flat");

            seedLesson(courseId, null, "One", 2);
            seedLesson(courseId, null, "Two", 2);
            seedLesson(courseId, null, "Three", 9);

            jdbc.update(NORMALISE);

            assertThat(positionsOf(courseId, null)).containsExactly(0, 1, 2);
            assertThat(titlesOf(courseId, null)).containsExactly("One", "Two", "Three");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a course with no lessons is left alone rather than failing on an empty scope")
    void anEmptyCourseIsFine() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("No lessons");

            jdbc.update(NORMALISE);

            assertThat(positionsOf(courseId, null)).isEmpty();
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the uniqueness constraint covers a flat course, where module_id is NULL throughout")
    void theConstraintCoversRootLessons() {
        // NULLS NOT DISTINCT is the whole point: without it Postgres would treat every root
        // lesson's scope as distinct from every other's and the constraint would cover nothing at
        // all where flat courses are concerned.
        Boolean nullsNotDistinct = jdbc.queryForObject("""
                SELECT i.indnullsnotdistinct
                FROM pg_constraint c
                         JOIN pg_index i ON i.indexrelid = c.conindid
                WHERE c.conname = 'uk_lessons_scope_order'
                """, Boolean.class);

        assertThat(nullsNotDistinct).isTrue();
    }

    @Test
    @DisplayName("the uniqueness constraint is deferred to COMMIT, so a permutation is writable")
    void theConstraintIsDeferred() {
        Boolean deferred = jdbc.queryForObject(
                "SELECT condeferred FROM pg_constraint WHERE conname = 'uk_lessons_scope_order'",
                Boolean.class);

        assertThat(deferred)
                .as("a reorder writes A:0->1 and B:1->0, which shares a position mid-transaction")
                .isTrue();
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedCourse(String title) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, title + " instructor",
                "lesson-migration-" + title.hashCode() + "@x.test");

        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id)
                VALUES (?, 'seeded', 'PUBLISHED', 'MODULES', 'FREE', 0, now(), ?) RETURNING id
                """, Long.class, title, instructorId);
    }

    private long seedModule(long courseId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO course_modules (title, order_index, course_id, created_at)
                VALUES (?, (SELECT COALESCE(MAX(order_index) + 1, 0) FROM course_modules WHERE course_id = ?),
                        ?, now()) RETURNING id
                """, Long.class, title, courseId, courseId);
    }

    private void seedLesson(long courseId, Long moduleId, String title, int orderIndex) {
        jdbc.update("""
                INSERT INTO lessons (title, video_url, order_index, duration, course_id, module_id, created_at)
                VALUES (?, 'https://example.test/v', ?, 0, ?, ?, now())
                """, title, orderIndex, courseId, moduleId);
    }

    private List<Integer> positionsOf(long courseId, Long moduleId) {
        return moduleId == null
                ? jdbc.query("SELECT order_index FROM lessons WHERE course_id = ? AND module_id IS NULL "
                        + "ORDER BY order_index, id", (rs, row) -> rs.getInt(1), courseId)
                : jdbc.query("SELECT order_index FROM lessons WHERE course_id = ? AND module_id = ? "
                        + "ORDER BY order_index, id", (rs, row) -> rs.getInt(1), courseId, moduleId);
    }

    private List<String> titlesOf(long courseId, Long moduleId) {
        return moduleId == null
                ? jdbc.query("SELECT title FROM lessons WHERE course_id = ? AND module_id IS NULL "
                        + "ORDER BY order_index, id", (rs, row) -> rs.getString(1), courseId)
                : jdbc.query("SELECT title FROM lessons WHERE course_id = ? AND module_id = ? "
                        + "ORDER BY order_index, id", (rs, row) -> rs.getString(1), courseId, moduleId);
    }
}
