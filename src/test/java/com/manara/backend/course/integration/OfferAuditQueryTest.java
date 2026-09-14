package com.manara.backend.course.integration;

import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.course.model.SubscriptionUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The TECH-1 offer audit, run against a real PostgreSQL before anybody runs it against production.
 *
 * <p>The query in {@code docs/catalog/offer-audit/} is the evidence Kashier's offer list is built
 * from, so a mistake in it is a wrong statement about what Manara sells. These tests execute the
 * committed file itself — not a copy of it — against every catalogue state the schema allows,
 * including the legacy ones the current validator would refuse to write: a purchase course with no
 * price, a subscription whose plans were all retired, a plan priced at zero.
 *
 * <p>Those states are written by SQL precisely because the application will not produce them any
 * more. Production data predates several of the rules, so the audit has to recognise them.
 *
 * <p>The database is shared across test classes and never cleaned, so every assertion is about the
 * courses this test created, looked up by id.
 *
 * <p>A pass here says the query is correct. It says nothing about production's catalogue, which
 * only a read-only run against production can.
 */
class OfferAuditQueryTest extends AbstractCourseAuthoringTest {

    private static final Path ELIGIBLE_OFFERS = Path.of("docs", "catalog", "offer-audit", "eligible-offers.sql");
    private static final Path EXCLUSIONS = Path.of("docs", "catalog", "offer-audit", "catalog-exclusions.sql");

    private static final Pattern WRITE_KEYWORD = Pattern.compile(
            "\\b(insert|update|delete|merge|alter|drop|truncate|create|grant|revoke|copy|call|do)\\b",
            Pattern.CASE_INSENSITIVE);

    @Autowired PlatformTransactionManager transactionManager;

    private final JsonMapper json = JsonMapper.builder().build();

    // ─── fixtures ────────────────────────────────────────────────────────────

    private Long freeCourse(String title, CourseStatus status, CourseVisibility visibility) {
        return courseService.createCourse(instructorUser,
                flatCourse(title, status, visibility, lesson("L1"))).getId();
    }

    private Long purchaseCourse(String title, String price) {
        var request = flatCourse(title, CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.PURCHASE);
        request.setPurchasePrice(new BigDecimal(price));
        return courseService.createCourse(instructorUser, request).getId();
    }

    private Long subscriptionCourse(String title, SubscriptionPlanRequest... plans) {
        var request = flatCourse(title, CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.SUBSCRIPTION);
        request.setSubscriptionPlans(List.of(plans));
        return courseService.createCourse(instructorUser, request).getId();
    }

    // ─── running the committed files ─────────────────────────────────────────

    private static String sqlOf(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Runs a committed audit file the way the runbook says to: inside a transaction the server
     * holds read-only. A query that tried to write would fail here as it would in production.
     */
    private List<Map<String, Object>> runReadOnly(Path file) {
        String sql = sqlOf(file);
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET TRANSACTION READ ONLY");
            return jdbcTemplate.queryForList(sql);
        });
    }

    private Map<Long, Map<String, Object>> auditedOffers() {
        return runReadOnly(ELIGIBLE_OFFERS).stream()
                .collect(Collectors.toMap(row -> ((Number) row.get("course_id")).longValue(), row -> row));
    }

    private List<JsonNode> plansOf(Map<String, Object> row) {
        return StreamSupport.stream(json.readTree((String) row.get("active_plans")).spliterator(), false)
                .toList();
    }

    private static List<String> findings(Map<String, Object> row, String column) {
        String value = (String) row.get(column);
        return value == null || value.isBlank() ? List.of() : List.of(value.split(", "));
    }

    // ─── eligibility ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("only PUBLISHED + PUBLIC courses are audited; every draft and private course is excluded")
    void onlyDiscoverableCoursesAreIncluded() {
        Long live = freeCourse("Live", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
        Long draftPublic = freeCourse("Draft public", CourseStatus.DRAFT, CourseVisibility.PUBLIC);
        Long livePrivate = freeCourse("Live private", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE);
        Long draftPrivate = freeCourse("Draft private", CourseStatus.DRAFT, CourseVisibility.PRIVATE);

        var offers = auditedOffers();

        assertThat(offers).containsKey(live);
        assertThat(offers).doesNotContainKeys(draftPublic, livePrivate, draftPrivate);
        for (Long id : offers.keySet()) {
            assertThat(reload(id).isDiscoverable())
                    .as("course %d is in the audit, so the entity must agree it is discoverable", id)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the report carries the commercial fields and no personal data")
    void theColumnsAreTheReportAndNothingElse() {
        Long id = freeCourse("Columns", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

        var row = auditedOffers().get(id);

        assertThat(row.keySet()).containsExactly(
                "query_version", "observed_at_utc", "course_id", "title", "status", "visibility",
                "access_type", "offer_status", "currency", "purchase_price", "active_plan_count",
                "valid_active_plan_count", "active_plans", "lesson_count", "last_published_at",
                "blocking_findings", "review_findings");
        assertThat(row.get("query_version")).isEqualTo("tech-1/v1");
        assertThat(row.get("observed_at_utc")).isNotNull();
        assertThat(row.get("status")).isEqualTo("PUBLISHED");
        assertThat(row.get("visibility")).isEqualTo("PUBLIC");
    }

    // ─── the three access types ──────────────────────────────────────────────

    @Test
    @DisplayName("FREE is decided by the access type, and carries no currency or price")
    void freeIsTheAccessTypeNotAPrice() {
        Long id = freeCourse("Free", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

        var row = auditedOffers().get(id);

        assertThat(row.get("access_type")).isEqualTo("FREE");
        assertThat(row.get("offer_status")).isEqualTo("FREE");
        assertThat(row.get("currency")).isNull();
        assertThat(row.get("purchase_price")).isNull();
        assertThat(findings(row, "blocking_findings")).isEmpty();
        assertThat(findings(row, "review_findings")).isEmpty();
    }

    @Test
    @DisplayName("PURCHASE reports its stored price in EGP")
    void purchaseReportsItsStoredPrice() {
        Long id = purchaseCourse("Bought outright", "450.00");

        var row = auditedOffers().get(id);

        assertThat(row.get("access_type")).isEqualTo("PURCHASE");
        assertThat(row.get("offer_status")).isEqualTo("PRICED");
        assertThat(row.get("currency")).isEqualTo("EGP");
        assertThat((BigDecimal) row.get("purchase_price")).isEqualByComparingTo("450.00");
        assertThat(findings(row, "blocking_findings")).isEmpty();
    }

    @Test
    @DisplayName("SUBSCRIPTION reports its active plans with price and term; a retired plan is not on offer")
    void subscriptionReportsOnlyActivePlans() {
        Long id = subscriptionCourse("Subscribed",
                plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00"),
                plan("Yearly", 12, SubscriptionUnit.MONTH, "900.00"));
        jdbcTemplate.update("UPDATE subscription_plans SET retired_at = now() WHERE course_id = ? AND name = 'Yearly'", id);

        var row = auditedOffers().get(id);
        var plans = plansOf(row);

        assertThat(row.get("offer_status")).isEqualTo("PRICED");
        assertThat(row.get("currency")).isEqualTo("EGP");
        assertThat(((Number) row.get("active_plan_count")).intValue()).isEqualTo(1);
        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().get("name").asString()).isEqualTo("Monthly");
        assertThat(plans.getFirst().get("duration").asInt()).isEqualTo(1);
        assertThat(plans.getFirst().get("unit").asString()).isEqualTo("MONTH");
        assertThat(plans.getFirst().get("price").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(plans.getFirst().get("valid").asBoolean()).isTrue();
        assertThat(findings(row, "review_findings")).doesNotContain("SUBSCRIPTION_MULTIPLE_PLANS");
    }

    @Test
    @DisplayName("several valid plans are reported in the instructor's order, and flagged for the owner to confirm")
    void multiplePlansAreOrderedAndFlagged() {
        Long id = subscriptionCourse("Choices",
                plan("Weekly", 1, SubscriptionUnit.WEEK, "40.00"),
                plan("Monthly", 1, SubscriptionUnit.MONTH, "120.00"));

        var row = auditedOffers().get(id);

        assertThat(plansOf(row)).extracting(p -> p.get("name").asString()).containsExactly("Weekly", "Monthly");
        assertThat(row.get("offer_status")).isEqualTo("PRICED");
        assertThat(findings(row, "review_findings")).contains("SUBSCRIPTION_MULTIPLE_PLANS");
    }

    // ─── commercial data that cannot be shown truthfully ─────────────────────

    @Test
    @DisplayName("a purchase course with no price is UNAVAILABLE and flagged — never free")
    void missingPurchasePriceIsFlagged() {
        Long id = purchaseCourse("Unpriced", "100.00");
        jdbcTemplate.update("UPDATE courses SET price = NULL WHERE id = ?", id);

        var row = auditedOffers().get(id);

        assertThat(row.get("access_type")).isEqualTo("PURCHASE");
        assertThat(row.get("offer_status")).isEqualTo("UNAVAILABLE");
        assertThat(row.get("currency")).isEqualTo("EGP");
        assertThat(row.get("purchase_price")).isNull();
        assertThat(findings(row, "blocking_findings")).containsExactly("PURCHASE_PRICE_MISSING");
    }

    @Test
    @DisplayName("a purchase course priced at zero is UNAVAILABLE and flagged — zero is not free")
    void zeroPurchasePriceIsFlagged() {
        Long id = purchaseCourse("Zero", "100.00");
        jdbcTemplate.update("UPDATE courses SET price = 0 WHERE id = ?", id);

        var row = auditedOffers().get(id);

        assertThat(row.get("offer_status")).isEqualTo("UNAVAILABLE");
        assertThat(findings(row, "blocking_findings")).containsExactly("PURCHASE_PRICE_NOT_POSITIVE");
    }

    @Test
    @DisplayName("a subscription whose plans were all retired is UNAVAILABLE and flagged")
    void subscriptionWithoutActivePlansIsFlagged() {
        Long id = subscriptionCourse("Retired", plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00"));
        jdbcTemplate.update("UPDATE subscription_plans SET retired_at = now() WHERE course_id = ?", id);

        var row = auditedOffers().get(id);

        assertThat(row.get("offer_status")).isEqualTo("UNAVAILABLE");
        assertThat(((Number) row.get("active_plan_count")).intValue()).isZero();
        assertThat(plansOf(row)).isEmpty();
        assertThat(findings(row, "blocking_findings")).containsExactly("SUBSCRIPTION_NO_ACTIVE_PLAN");
    }

    @Test
    @DisplayName("a subscription whose only plan is priced at zero is UNAVAILABLE, and the plan is marked invalid")
    void subscriptionWithOnlyInvalidPlansIsFlagged() {
        Long id = subscriptionCourse("Broken", plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00"));
        jdbcTemplate.update("UPDATE subscription_plans SET price = 0 WHERE course_id = ?", id);

        var row = auditedOffers().get(id);

        assertThat(row.get("offer_status")).isEqualTo("UNAVAILABLE");
        assertThat(plansOf(row).getFirst().get("valid").asBoolean()).isFalse();
        assertThat(findings(row, "blocking_findings")).containsExactly("SUBSCRIPTION_NO_VALID_PLAN");
    }

    @Test
    @DisplayName("an invalid plan beside a valid one leaves the offer priced, and asks the owner to review it")
    void invalidPlanBesideAValidOneIsReviewed() {
        Long id = subscriptionCourse("Mixed",
                plan("Good", 1, SubscriptionUnit.MONTH, "100.00"),
                plan("Bad", 1, SubscriptionUnit.WEEK, "30.00"));
        jdbcTemplate.update("UPDATE subscription_plans SET price = 0 WHERE course_id = ? AND name = 'Bad'", id);

        var row = auditedOffers().get(id);

        assertThat(row.get("offer_status")).isEqualTo("PRICED");
        assertThat(((Number) row.get("valid_active_plan_count")).intValue()).isEqualTo(1);
        assertThat(findings(row, "blocking_findings")).isEmpty();
        assertThat(findings(row, "review_findings")).contains("SUBSCRIPTION_HAS_INVALID_PLAN");
    }

    @Test
    @DisplayName("a free course still carrying an old price stays FREE, and the leftover price is flagged")
    void stalePriceOnAFreeCourseIsReviewed() {
        Long id = freeCourse("Leftover", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
        jdbcTemplate.update("UPDATE courses SET price = 99 WHERE id = ?", id);

        var row = auditedOffers().get(id);

        assertThat(row.get("offer_status")).isEqualTo("FREE");
        assertThat(findings(row, "review_findings")).contains("STALE_PURCHASE_PRICE");
    }

    @Test
    @DisplayName("a published course with no lessons and no description is flagged for the owner")
    void incompleteContentIsFlagged() {
        Long id = freeCourse("Empty", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
        jdbcTemplate.update("DELETE FROM lessons WHERE course_id = ?", id);
        jdbcTemplate.update("UPDATE courses SET description = NULL WHERE id = ?", id);

        var row = auditedOffers().get(id);

        assertThat(findings(row, "blocking_findings")).contains("NO_LESSONS");
        assertThat(findings(row, "review_findings")).contains("DESCRIPTION_MISSING");
    }

    // ─── exclusions ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the exclusion summary counts every combination, and names no excluded course")
    void exclusionSummaryIsCountsOnly() {
        freeCourse("Hidden draft", CourseStatus.DRAFT, CourseVisibility.PUBLIC);
        freeCourse("Hidden private", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE);

        var rows = runReadOnly(EXCLUSIONS);

        assertThat(rows).isNotEmpty();
        assertThat(rows.getFirst().keySet()).containsExactly(
                "query_version", "observed_at_utc", "status", "visibility", "access_type", "eligible",
                "course_count");
        for (var row : rows) {
            boolean discoverable = "PUBLISHED".equals(row.get("status")) && "PUBLIC".equals(row.get("visibility"));
            assertThat(row.get("eligible")).isEqualTo(discoverable);
        }
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("eligible")).isEqualTo(false);
        });
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.get("visibility")).isEqualTo("PRIVATE");
            assertThat(row.get("eligible")).isEqualTo(false);
        });
    }

    // ─── read-only by construction ───────────────────────────────────────────

    @Test
    @DisplayName("the committed audit files contain no statement that writes")
    void theFilesOnlyRead() {
        for (Path file : List.of(ELIGIBLE_OFFERS, EXCLUSIONS)) {
            String code = sqlOf(file).lines()
                    .map(line -> line.replaceFirst("--.*$", ""))
                    .collect(Collectors.joining("\n"));
            assertThat(WRITE_KEYWORD.matcher(code).find())
                    .as("%s must be a read-only query", file)
                    .isFalse();
        }
    }

    /**
     * The negative control for the runbook's guard. The read-only session is what protects
     * production if this file is ever edited into a write, so it has to be shown to refuse one.
     */
    @Test
    @DisplayName("the read-only session the runbook uses refuses a write")
    void theReadOnlySessionRefusesAWrite() {
        Long id = freeCourse("Guarded", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbcTemplate.execute("SET TRANSACTION READ ONLY");
            jdbcTemplate.update("UPDATE courses SET title = 'Changed' WHERE id = ?", id);
        }))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("read-only transaction");
        assertThat(reload(id).getTitle()).isEqualTo("Guarded");
    }
}
