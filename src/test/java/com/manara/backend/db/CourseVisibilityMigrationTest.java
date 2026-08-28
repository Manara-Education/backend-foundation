package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12, against rows that were already there.
 *
 * <p>{@link FlywayMigrationTest} proves the migration runs; it runs against an empty container, so
 * it says nothing about a database with courses in it. The single thing V12 has to get right for
 * an existing database is that <em>no course goes dark</em>: every row that existed before the
 * column did was on offer to everyone, and must still be the moment the column appears. A default
 * that was missed anywhere — the column's, the back-fill's, or an instance still running the
 * previous build inserting without naming it — takes a live course off the catalogue silently,
 * which is the worst outcome this migration could have and the reason it states the default three
 * separate ways.
 *
 * <p>The other half is that the two axes stayed two. {@code courses_status_check} must still name
 * exactly {@code DRAFT} and {@code PUBLISHED}: the moment {@code PRIVATE} appears in it, a
 * published private course has become inexpressible and "make this private" has started destroying
 * the publication baseline every learner's update badge is measured against.
 */
class CourseVisibilityMigrationTest extends AbstractPostgresBackedTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("a course row that predates the column is PUBLIC, not null and not hidden")
    void existingCoursesAreScopedPublic() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Pre-existing", "PUBLISHED");

            // Inserted without naming the column, exactly as an instance running the previous build
            // would — the default is what has to be right, not the application.
            assertThat(jdbc.queryForObject(
                    "SELECT visibility FROM courses WHERE id = ?", String.class, courseId))
                    .isEqualTo("PUBLIC");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a pre-existing published course is still discoverable by the catalogue's predicate")
    void existingPublishedCoursesStayInTheCatalogue() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Live before V12", "PUBLISHED");

            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM courses
                    WHERE id = ? AND status = 'PUBLISHED' AND visibility = 'PUBLIC'
                    """, Integer.class, courseId))
                    .as("the whole promise of the migration: nothing disappears from the catalogue")
                    .isOne();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the column is NOT NULL and refuses a value no Java constant matches")
    void theColumnIsConstrained() {
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'courses' AND column_name = 'visibility'
                """, String.class)).isEqualTo("NO");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'ck_courses_visibility'
                """, Integer.class)).isOne();

        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Constrained", "PUBLISHED");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE courses SET visibility = 'HIDDEN' WHERE id = ?", courseId))
                    .isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });
    }

    /**
     * The axes stayed separate, which is the design decision the schema has to keep enforcing.
     */
    @Test
    @DisplayName("status still names exactly DRAFT and PUBLISHED — PRIVATE is not a status")
    void privateIsNotAStatus() {
        String statusCheck = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'courses_status_check'
                """, String.class);

        assertThat(statusCheck).contains("DRAFT").contains("PUBLISHED");
        assertThat(statusCheck)
                .as("folding visibility into the lifecycle would make PUBLISHED+PRIVATE unrepresentable")
                .doesNotContain("PRIVATE");
    }

    @Test
    @DisplayName("all four combinations of the two axes are storable")
    void everyCombinationIsLegal() {
        transactionTemplate.executeWithoutResult(status -> {
            for (String courseStatus : new String[]{"DRAFT", "PUBLISHED"}) {
                for (String visibility : new String[]{"PUBLIC", "PRIVATE"}) {
                    long courseId = seedCourse(courseStatus + "-" + visibility, courseStatus);
                    jdbc.update("UPDATE courses SET visibility = ? WHERE id = ?", visibility, courseId);

                    assertThat(jdbc.queryForObject(
                            "SELECT status || '+' || visibility FROM courses WHERE id = ?",
                            String.class, courseId))
                            .isEqualTo(courseStatus + "+" + visibility);
                }
            }
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the catalogue has its partial index to run on")
    void discoveryIsIndexed() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'idx_courses_discoverable'
                """, Integer.class)).isOne();
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedCourse(String title, String courseStatus) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, title + " instructor",
                "v12-migration-" + title.hashCode() + "@x.test");

        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        // Deliberately without naming `visibility`: this is the insert an instance running the
        // previous build makes, and its rows have to come out public.
        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id)
                VALUES (?, 'seeded', ?, 'FLAT', 'FREE', 0, now(), ?) RETURNING id
                """, Long.class, title, courseStatus, instructorId);
    }
}
