package com.manara.backend.course.integration;

import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20's backfill, run against purchase and subscription rows written the way the application wrote
 * them before the ledger existed. The statements are taken from the migration file itself, so what
 * is tested is what production runs.
 */
class PaymentLedgerBackfillMigrationTest extends AbstractCourseAuthoringTest {

    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V20__payment_transactions_and_receipts.sql");

    @Test
    @DisplayName("history becomes SIMULATED or LEGACY rows with recorded amounts only, no receipts, and a rerun adds nothing")
    void backfill() throws IOException {
        Long course = courseService.createCourse(instructorUser,
                flatCourse("Old course", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"))).getId();
        User simulatedBuyer = newStudentUser();
        User legacyBuyer = newStudentUser();
        User subscriber = newStudentUser();
        jdbcTemplate.update("""
                INSERT INTO course_purchases (course_id, student_id, list_price, amount_paid, currency, payment_reference, purchased_at, created_at)
                VALUES (?, ?, 300, 280, 'EGP', 'sim_old', '2026-03-01 10:00', now()),
                       (?, ?, 300, 300, 'EGP', NULL, '2026-03-02 10:00', now())
                """, course, studentProfileOf(simulatedBuyer).getId(), course, studentProfileOf(legacyBuyer).getId());
        Long planId = jdbcTemplate.queryForObject("""
                INSERT INTO subscription_plans (name, duration, unit, price, order_index, course_id, created_at)
                VALUES ('Old plan', 1, 'MONTH', 99, 0, ?, now()) RETURNING id
                """, Long.class, course);
        // The plan is repriced afterwards: history must keep what was charged, not today's price.
        jdbcTemplate.update("""
                INSERT INTO course_subscriptions (course_id, student_id, subscription_plan_id, starts_at, expires_at, status,
                    price_paid, payment_reference, created_at)
                VALUES (?, ?, ?, '2026-04-01', '2026-05-01', 'EXPIRED', 99, 'sim_sub', '2026-04-01 09:00')
                """, course, studentProfileOf(subscriber).getId(), planId);
        jdbcTemplate.update("UPDATE subscription_plans SET price = 500 WHERE id = ?", planId);

        String backfill = backfillStatements();
        runAll(backfill);

        Map<String, Object> simulated = row(simulatedBuyer);
        assertThat(simulated.get("provenance")).isEqualTo("SIMULATED");
        assertThat(simulated.get("amount").toString()).isEqualTo("280.00");
        assertThat(simulated.get("gateway_reference")).isEqualTo("sim_old");
        assertThat(row(legacyBuyer).get("provenance")).isEqualTo("LEGACY");
        Map<String, Object> subscription = row(subscriber);
        assertThat(subscription.get("amount").toString()).isEqualTo("99.00");
        assertThat(subscription.get("currency")).isEqualTo("EGP");
        assertThat(subscription.get("purpose")).isEqualTo("SUBSCRIPTION");
        assertThat(subscription.get("line_description")).isEqualTo("Old course - Old plan");

        long receipts = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM payment_receipts r JOIN payment_transactions t ON t.id = r.transaction_id
                WHERE t.source_key LIKE 'purchase:%' OR t.source_key LIKE 'subscription:%'
                """, Long.class);
        assertThat(receipts).as("no receipt is invented for history").isZero();

        long before = jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions", Long.class);
        runAll(backfill);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions", Long.class)).isEqualTo(before);
    }

    private Map<String, Object> row(User student) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM payment_transactions WHERE student_id = ?", studentProfileOf(student).getId());
        assertThat(rows).hasSize(1);
        return rows.getFirst();
    }

    private static String backfillStatements() throws IOException {
        String sql = Files.readString(MIGRATION, StandardCharsets.UTF_8);
        return sql.substring(sql.indexOf("-- Backfill: purchases"));
    }

    private void runAll(String sql) {
        for (String statement : sql.split(";")) {
            String stripped = statement.lines().filter(line -> !line.trim().startsWith("--")).reduce("", (a, b) -> a + "\n" + b).trim();
            if (!stripped.isEmpty()) jdbcTemplate.execute(stripped);
        }
    }
}
