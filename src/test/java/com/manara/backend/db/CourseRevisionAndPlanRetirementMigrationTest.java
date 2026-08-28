package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8, against rows that were already there.
 *
 * <p>{@link FlywayMigrationTest} proves the migration runs; it runs against an empty container, so
 * it says nothing about a database with courses and plans in it. Both of V8's columns are additive
 * and both have to describe existing rows correctly the moment they appear — a course that has
 * never been edited by this build is at revision {@code 0}, and a plan that exists is one that is
 * still being offered.
 *
 * <p>The foreign keys from {@code course_entitlements} and {@code course_subscriptions} to
 * {@code subscription_plans} are deliberately left as they were, and that is asserted here too:
 * cascading them would delete a learner's subscription to satisfy an instructor's tidy-up, and
 * setting them null would erase which plan somebody bought. Retirement exists so that neither has
 * to happen.
 */
class CourseRevisionAndPlanRetirementMigrationTest extends AbstractPostgresBackedTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("a course row that predates the column reads as revision 0, not as no revision")
    void existingCoursesStartAtRevisionZero() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Pre-existing");

            // Inserted without naming the column, exactly as an instance running the previous build
            // would — the default is what has to be right, not the application.
            assertThat(jdbc.queryForObject(
                    "SELECT revision FROM courses WHERE id = ?", Long.class, courseId)).isZero();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the revision column is NOT NULL and cannot go backwards past zero")
    void theRevisionIsConstrained() {
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'courses' AND column_name = 'revision'
                """, String.class)).isEqualTo("NO");

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint
                WHERE conname = 'ck_courses_revision_non_negative'
                """, Integer.class)).isOne();
    }

    @Test
    @DisplayName("every plan that already existed is still being offered")
    void existingPlansAreNotRetired() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Has plans");
            long planId = seedPlan(courseId, "Monthly");

            assertThat(jdbc.queryForObject(
                    "SELECT retired_at FROM subscription_plans WHERE id = ?", Object.class, planId))
                    .as("NULL is the whole of the back-fill: an existing plan is an offered plan")
                    .isNull();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the offer lookup has a partial index to run on")
    void activePlansAreIndexed() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND indexname = 'idx_subscription_plans_course_active'
                """, Integer.class)).isOne();
    }

    /**
     * The constraint that produced the original defect, kept exactly as it was.
     *
     * <p>{@code NO ACTION} on both references is correct and is the reason retirement had to be
     * built: the database is right to refuse deleting a plan somebody bought, and the application
     * was wrong to be asking.
     */
    @Test
    @DisplayName("the references from entitlements and subscriptions still refuse a delete")
    void planReferencesStillRefuseADelete() {
        assertThat(jdbc.queryForList("""
                SELECT t.relname AS referencing, c.confdeltype
                FROM pg_constraint c
                         JOIN pg_class t ON t.oid = c.conrelid
                         JOIN pg_class referenced ON referenced.oid = c.confrelid
                WHERE c.contype = 'f' AND referenced.relname = 'subscription_plans'
                """))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("confdeltype"))
                        .as("'a' is NO ACTION; anything else would delete or blank a bought term")
                        .isEqualTo("a"));
    }

    @Test
    @DisplayName("retiring a plan an entitlement points at is a legal write; deleting it is not")
    void retirementIsWritableWhereDeletionIsNot() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Subscribed");
            long planId = seedPlan(courseId, "Monthly");
            seedEntitlementAgainst(courseId, planId);

            jdbc.update("UPDATE subscription_plans SET retired_at = now() WHERE id = ?", planId);

            assertThat(jdbc.queryForObject(
                    "SELECT retired_at IS NOT NULL FROM subscription_plans WHERE id = ?",
                    Boolean.class, planId)).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM course_entitlements WHERE subscription_plan_id = ?",
                    Integer.class, planId)).isOne();

            status.setRollbackOnly();
        });
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedCourse(String title) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, title + " instructor",
                "v8-migration-" + title.hashCode() + "@x.test");

        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id)
                VALUES (?, 'seeded', 'PUBLISHED', 'FLAT', 'SUBSCRIPTION', 0, now(), ?) RETURNING id
                """, Long.class, title, instructorId);
    }

    private long seedPlan(long courseId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO subscription_plans (name, duration, unit, price, order_index, course_id, created_at)
                VALUES (?, 1, 'MONTH', 100.00, 0, ?, now()) RETURNING id
                """, Long.class, name, courseId);
    }

    private void seedEntitlementAgainst(long courseId, long planId) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('A learner', ?, 'x', true, false, 'STUDENT', now()) RETURNING id
                """, Long.class, "v8-learner-" + courseId + "@x.test");
        Long studentId = jdbc.queryForObject(
                "INSERT INTO students (user_id) VALUES (?) RETURNING id", Long.class, userId);

        jdbc.update("""
                INSERT INTO course_entitlements (course_id, student_id, source, subscription_plan_id,
                                                 starts_at, expires_at, created_at)
                VALUES (?, ?, 'SUBSCRIPTION', ?, now(), now() + interval '30 days', now())
                """, courseId, studentId, planId);
    }
}
