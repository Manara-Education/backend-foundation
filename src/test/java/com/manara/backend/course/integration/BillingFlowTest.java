package com.manara.backend.course.integration;

import com.jayway.jsonpath.JsonPath;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ledger as a student meets it: checkout writes it, the billing endpoints read it back, and
 * nothing a demonstration deployment does is ever counted as money received. Tests run in the
 * default DEMONSTRATION mode, so every charge here is simulated.
 */
class BillingFlowTest extends AbstractCourseAuthoringTest {

    private MockMvc mockMvc;

    @Autowired
    WebApplicationContext context;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── Checkout writes the ledger ────────────────────────────────────────────

    @Test
    @DisplayName("A purchase records one simulated transaction and a DEMO receipt, at the server's price")
    void purchaseIsRecorded() throws Exception {
        Long course = purchaseCourse("450.00");
        User student = newStudentUser();

        // The client names an amount; nothing reads it.
        String body = checkout(student, course, "{\"amount\":1,\"paymentMethod\":{\"name\":\"سارة\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionStatus").value("PAID"))
                .andExpect(jsonPath("$.data.amount").value(450.00))
                .andExpect(jsonPath("$.data.currency").value("EGP"))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andReturn().getResponse().getContentAsString();
        String reference = JsonPath.read(body, "$.data.transactionId");
        String receipt = JsonPath.read(body, "$.data.receiptNumber");
        assertThat(receipt).matches("DEMO-\\d{4}-\\d{6,}");

        mockMvc.perform(get("/api/v1/student/transactions/{ref}", reference).with(signedIn(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.provenance").value("SIMULATED"))
                .andExpect(jsonPath("$.data.summary.courseAccess").value("ACTIVE"))
                .andExpect(jsonPath("$.data.total").value(450.00))
                .andExpect(jsonPath("$.data.lines[0].description").value("Priced course"));

        // A repeat of the same checkout charges nothing and records nothing.
        checkout(student, course, "{\"paymentMethod\":{\"name\":\"سارة\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactionId").value(nullValue()));
        assertThat(transactionCount(student)).isOne();
    }

    @Test
    @DisplayName("A subscription is listed as a fixed term, linked to its transaction, and expires honestly")
    void subscriptionIsFixedTerm() throws Exception {
        Long course = subscriptionCourse();
        User student = newStudentUser();
        Long planId = jdbcTemplate.queryForObject("SELECT id FROM subscription_plans WHERE course_id = ?", Long.class, course);
        checkout(student, course, "{\"planId\":" + planId + ",\"paymentMethod\":{\"name\":\"سارة\"}}").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/student/subscriptions").with(signedIn(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].renewalMode").value("FIXED"))
                .andExpect(jsonPath("$.data.items[0].displayStatus").value("FIXED_ACCESS"))
                .andExpect(jsonPath("$.data.items[0].pricePaid").value(120.00))
                .andExpect(jsonPath("$.data.items[0].provenance").value("SIMULATED"))
                .andExpect(jsonPath("$.data.items[0].transactionReference").isString())
                .andExpect(jsonPath("$.data.activeCount").value(1));

        jdbcTemplate.update("UPDATE course_subscriptions SET expires_at = now() - interval '1 day' WHERE student_id = ?",
                studentProfileOf(student).getId());
        mockMvc.perform(get("/api/v1/student/subscriptions").with(signedIn(student)))
                .andExpect(jsonPath("$.data.items[0].displayStatus").value("EXPIRED"))
                .andExpect(jsonPath("$.data.activeCount").value(0));
    }

    // ── Totals only count money received ──────────────────────────────────────

    @Test
    @DisplayName("Simulated and legacy rows never reach the total; currencies are never added together")
    void totalsCountOnlyLiveMoney() throws Exception {
        Long course = purchaseCourse("450.00");
        User student = newStudentUser();
        checkout(student, course, "{\"paymentMethod\":{\"name\":\"سارة\"}}").andExpect(status().isOk());
        Long studentId = studentProfileOf(student).getId();
        // As a real provider would record them: two currencies, one of them twice.
        liveRow(studentId, course, "100.00", "EGP");
        liveRow(studentId, course, "50.00", "EGP");
        liveRow(studentId, course, "20.00", "USD");

        mockMvc.perform(get("/api/v1/student/transactions").with(signedIn(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalItems").value(4))
                .andExpect(jsonPath("$.data.confirmedTotals.length()").value(2))
                .andExpect(jsonPath("$.data.confirmedTotals[0].currency").value("EGP"))
                .andExpect(jsonPath("$.data.confirmedTotals[0].amount").value(150.00))
                .andExpect(jsonPath("$.data.confirmedTotals[1].currency").value("USD"))
                .andExpect(jsonPath("$.data.provenanceCounts.SIMULATED").value(1))
                .andExpect(jsonPath("$.data.provenanceCounts.LIVE").value(3));
    }

    // ── Filters and paging ────────────────────────────────────────────────────

    @Test
    @DisplayName("Filters apply before paging and to the summary; bad parameters are refused")
    void filtersAndPaging() throws Exception {
        Long course = purchaseCourse("450.00");
        User student = newStudentUser();
        String body = checkout(student, course, "{\"paymentMethod\":{\"name\":\"سارة\"}}").andReturn().getResponse().getContentAsString();
        String receipt = JsonPath.read(body, "$.data.receiptNumber");
        Long studentId = studentProfileOf(student).getId();
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (reference, student_id, course_id, purpose, line_description, amount,
                    currency, status, provenance, created_at, source_key)
                VALUES (?, ?, ?, 'PURCHASE', 'Old failed attempt', 450, 'EGP', 'FAILED', 'LIVE', '2026-01-10 12:00', ?)
                """, UUID.randomUUID(), studentId, course, "test:" + UUID.randomUUID());

        mockMvc.perform(get("/api/v1/student/transactions").param("q", receipt.toLowerCase()).with(signedIn(student)))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(get("/api/v1/student/transactions").param("status", "FAILED").with(signedIn(student)))
                .andExpect(jsonPath("$.data.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED"));
        // `to` is inclusive: the whole of 2026-01-10 is in range; the day before is not.
        mockMvc.perform(get("/api/v1/student/transactions").param("from", "2026-01-10").param("to", "2026-01-10").with(signedIn(student)))
                .andExpect(jsonPath("$.data.totalItems").value(1));
        mockMvc.perform(get("/api/v1/student/transactions").param("to", "2026-01-09").with(signedIn(student)))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        mockMvc.perform(get("/api/v1/student/transactions").param("size", "1").with(signedIn(student)))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.totalItems").value(2));

        for (var bad : List.of(new String[]{"size", "51"}, new String[]{"status", "SETTLED"}, new String[]{"page", "-1"})) {
            mockMvc.perform(get("/api/v1/student/transactions").param(bad[0], bad[1]).with(signedIn(student)))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/api/v1/student/transactions").param("from", "2026-02-01").param("to", "2026-01-01").with(signedIn(student)))
                .andExpect(status().isBadRequest());
    }

    // ── Ownership ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Another student's transaction, receipt and PDF are answered as not found")
    void recordsAreOwnerScoped() throws Exception {
        Long course = purchaseCourse("450.00");
        User owner = newStudentUser();
        User other = newStudentUser();
        String body = checkout(owner, course, "{\"paymentMethod\":{\"name\":\"سارة\"}}").andReturn().getResponse().getContentAsString();
        String reference = JsonPath.read(body, "$.data.transactionId");
        String receipt = JsonPath.read(body, "$.data.receiptNumber");

        mockMvc.perform(get("/api/v1/student/transactions/{ref}", reference).with(signedIn(other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/student/receipts/{n}", receipt).with(signedIn(other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/student/receipts/{n}/pdf", receipt).with(signedIn(other))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/student/transactions").with(signedIn(other)))
                .andExpect(jsonPath("$.data.totalItems").value(0));
        mockMvc.perform(get("/api/v1/student/transactions/{ref}", "not-a-uuid").with(signedIn(owner))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/student/transactions").with(signedIn(instructorUser))).andExpect(status().isBadRequest());
    }

    // ── Receipts ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A receipt keeps what was true when it was issued, and downloads as a PDF")
    void receiptsAreSnapshots() throws Exception {
        Long course = purchaseCourse("450.00");
        User student = newStudentUser();
        String body = checkout(student, course, "{\"paymentMethod\":{\"name\":\"سارة\"}}").andReturn().getResponse().getContentAsString();
        String receipt = JsonPath.read(body, "$.data.receiptNumber");
        jdbcTemplate.update("UPDATE courses SET title = 'Renamed later', price = 999 WHERE id = ?", course);
        jdbcTemplate.update("UPDATE users SET full_name = 'Renamed person' WHERE id = ?", student.getId());

        mockMvc.perform(get("/api/v1/student/receipts/{n}", receipt).with(signedIn(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lineDescription").value("Priced course"))
                .andExpect(jsonPath("$.data.amount").value(450.00))
                .andExpect(jsonPath("$.data.customerName").value(student.getFullName()))
                .andExpect(jsonPath("$.data.simulated").value(true))
                .andExpect(jsonPath("$.data.fiscal").value(false));

        byte[] pdf = mockMvc.perform(get("/api/v1/student/receipts/{n}/pdf", receipt).with(signedIn(student)))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
    }

    // ── Quote ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The quote prices from the server and says the term is fixed")
    void quote() throws Exception {
        Long purchase = purchaseCourse("450.00");
        Long subscription = subscriptionCourse();
        Long planId = jdbcTemplate.queryForObject("SELECT id FROM subscription_plans WHERE course_id = ?", Long.class, subscription);
        Long otherPlan = jdbcTemplate.queryForObject("SELECT id FROM subscription_plans WHERE course_id <> ? LIMIT 1", Long.class, subscription);
        User student = newStudentUser();

        quote(student, purchase, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amount").value(450.00))
                .andExpect(jsonPath("$.data.accessKind").value("PERPETUAL"))
                .andExpect(jsonPath("$.data.renewalMode").value(nullValue()))
                .andExpect(jsonPath("$.data.payable").value(true))
                .andExpect(jsonPath("$.data.simulated").value(true));
        quote(student, subscription, "{\"planId\":" + planId + "}")
                .andExpect(jsonPath("$.data.amount").value(120.00))
                .andExpect(jsonPath("$.data.accessKind").value("FIXED_TERM"))
                .andExpect(jsonPath("$.data.accessDuration").value(1))
                .andExpect(jsonPath("$.data.accessUnit").value("MONTH"))
                .andExpect(jsonPath("$.data.renewalMode").value("FIXED"));
        quote(student, subscription, "{}").andExpect(status().isBadRequest());
        if (otherPlan != null) quote(student, subscription, "{\"planId\":" + otherPlan + "}").andExpect(status().isBadRequest());
        assertThat(transactionCount(student)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long purchaseCourse(String price) {
        var request = flatCourse("Priced course", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.PURCHASE);
        request.setPurchasePrice(new BigDecimal(price));
        return courseService.createCourse(instructorUser, request).getId();
    }

    private Long subscriptionCourse() {
        var request = flatCourse("Subscribed course", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.SUBSCRIPTION);
        request.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "120.00")));
        return courseService.createCourse(instructorUser, request).getId();
    }

    private ResultActions checkout(User student, Long course, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/student/courses/{id}/checkout", course).with(csrf()).with(signedIn(student))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions quote(User student, Long course, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/student/courses/{id}/checkout/quote", course).with(csrf()).with(signedIn(student))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private void liveRow(Long studentId, Long course, String amount, String currency) {
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (reference, student_id, course_id, purpose, line_description, amount,
                    currency, status, provenance, created_at, paid_at, source_key)
                VALUES (?, ?, ?, 'PURCHASE', 'Live row', ?, ?, 'PAID', 'LIVE', now(), now(), ?)
                """, UUID.randomUUID(), studentId, course, new BigDecimal(amount), currency, "test:" + UUID.randomUUID());
    }

    private int transactionCount(User student) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM payment_transactions WHERE student_id = ?", Integer.class,
                studentProfileOf(student).getId());
    }

    @SuppressWarnings("unused")
    private static LocalDate today() {
        return LocalDate.now();
    }
}
