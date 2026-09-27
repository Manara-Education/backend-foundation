package com.manara.backend.course.integration;

import com.jayway.jsonpath.JsonPath;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Refund requests with the switch on, as a deployment with a review process would run them. A request
 * is a request: it never changes the payment, the paid totals or access, and only a live payment inside
 * the window qualifies. (Off — the default — is covered in {@link BillingFlowTest}.)
 *
 * <p>The switch needs its own application context. It is closed after this class and kept to a small
 * pool, so it does not add to the connections every cached context holds on the shared database.
 */
@TestPropertySource(properties = {"app.refund-requests.enabled=true", "spring.datasource.hikari.maximum-pool-size=4"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RefundRequestFlowTest extends AbstractCourseAuthoringTest {

    private static final String REQUEST = "{\"reason\":\"ACCESS_PROBLEM\",\"note\":\"  لا أستطيع فتح الدروس  \",\"amount\":1}";

    private MockMvc mockMvc;

    @Autowired
    WebApplicationContext context;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("A live payment can be put up for review once; the payment, totals and access are untouched")
    void requestIsOnlyARequest() throws Exception {
        User student = newStudentUser();
        Long course = purchaseCourse();
        String reference = liveRow(studentProfileOf(student).getId(), course, "450.00", "0 days");

        mockMvc.perform(get("/api/v1/student/billing/capabilities").with(signedIn(student)))
                .andExpect(jsonPath("$.data.refundRequests").value(true))
                .andExpect(jsonPath("$.data.refunds").value(false));
        detail(student, reference).andExpect(jsonPath("$.data.refundEligibility").value("ELIGIBLE"));

        // The client names an amount; the server refunds what remains, and trims the note.
        submit(student, reference, REQUEST)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.amount").value(450.00))
                .andExpect(jsonPath("$.data.currency").value("EGP"))
                .andExpect(jsonPath("$.data.note").value("لا أستطيع فتح الدروس"))
                .andExpect(jsonPath("$.data.transactionReference").value(reference))
                .andExpect(jsonPath("$.data.decidedAt").doesNotExist());

        detail(student, reference)
                .andExpect(jsonPath("$.data.summary.status").value("PAID"))
                .andExpect(jsonPath("$.data.refundedAmount").value(0))
                .andExpect(jsonPath("$.data.refundEligibility").value("REQUEST_OPEN"));
        mockMvc.perform(get("/api/v1/student/transactions").with(signedIn(student)))
                .andExpect(jsonPath("$.data.confirmedTotals[0].amount").value(450.00));

        submit(student, reference, REQUEST)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_REQUEST_OPEN"));
        mockMvc.perform(get("/api/v1/student/transactions/{ref}/refund-requests", reference).with(signedIn(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].reason").value("ACCESS_PROBLEM"));
    }

    @Test
    @DisplayName("No reason is needed: the Terms grant the refund without one")
    void reasonIsOptional() throws Exception {
        User student = newStudentUser();
        String reference = liveRow(studentProfileOf(student).getId(), purchaseCourse(), "450.00", "13 days");
        submit(student, reference, "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.reason").value(nullValue()))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));
    }

    @Test
    @DisplayName("A simulated payment, a payment outside the window, and a pending one are refused with the reason")
    void ineligible() throws Exception {
        User student = newStudentUser();
        Long course = purchaseCourse();
        String simulated = JsonPath.read(mockMvc.perform(post("/api/v1/student/courses/{id}/checkout", course)
                        .with(csrf()).with(signedIn(student)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethod\":{\"name\":\"سارة\"}}"))
                .andReturn().getResponse().getContentAsString(), "$.data.transactionId");
        Long studentId = studentProfileOf(student).getId();
        String old = liveRow(studentId, course, "450.00", "20 days");
        String pending = liveRow(studentId, course, "450.00", "0 days");
        jdbcTemplate.update("UPDATE payment_transactions SET status = 'PROCESSING', paid_at = NULL WHERE reference = ?::uuid", pending);

        detail(student, simulated).andExpect(jsonPath("$.data.refundEligibility").value("NOT_LIVE"));
        submit(student, simulated, REQUEST).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ELIGIBLE"));
        submit(student, old, REQUEST).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REFUND_NOT_ELIGIBLE"));
        detail(student, old).andExpect(jsonPath("$.data.refundEligibility").value("WINDOW_CLOSED"));
        detail(student, pending).andExpect(jsonPath("$.data.refundEligibility").value("NOT_PAID"));
        assertThat(requestCount(student)).isZero();
    }

    @Test
    @DisplayName("Another student's payment is not found; an unknown reason or an oversized note is invalid")
    void ownershipAndValidation() throws Exception {
        User owner = newStudentUser();
        User other = newStudentUser();
        String reference = liveRow(studentProfileOf(owner).getId(), purchaseCourse(), "450.00", "0 days");

        submit(other, reference, REQUEST).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/student/transactions/{ref}/refund-requests", reference).with(signedIn(other)))
                .andExpect(status().isNotFound());
        submit(owner, reference, "{\"reason\":\"FREE_MONEY\"}").andExpect(status().isBadRequest());
        submit(owner, reference, "{\"reason\":\"OTHER\",\"note\":\"" + "x".repeat(1001) + "\"}").andExpect(status().isBadRequest());
        assertThat(requestCount(owner) + requestCount(other)).isZero();
    }

    @Test
    @DisplayName("Concurrent submissions for one payment open exactly one request")
    void concurrentSubmissions() throws Exception {
        User student = newStudentUser();
        String reference = liveRow(studentProfileOf(student).getId(), purchaseCourse(), "450.00", "0 days");
        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> answers = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<Integer> call = () -> {
                    start.await();
                    return submit(student, reference, REQUEST).andReturn().getResponse().getStatus();
                };
                answers.add(pool.submit(call));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) statuses.add(answer.get());
            assertThat(statuses).containsOnly(201, 409);
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(requestCount(student)).isOne();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long purchaseCourse() {
        var request = flatCourse("Refundable course", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.PURCHASE);
        request.setPurchasePrice(new BigDecimal("450.00"));
        return courseService.createCourse(instructorUser, request).getId();
    }

    /** A payment as a real provider would have recorded it, paid {@code age} ago. */
    private String liveRow(Long studentId, Long course, String amount, String age) {
        UUID reference = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO payment_transactions (reference, student_id, course_id, purpose, line_description, amount,
                    currency, status, provenance, created_at, paid_at, source_key)
                VALUES (?, ?, ?, 'PURCHASE', 'Live row', ?, 'EGP', 'PAID', 'LIVE',
                    now() - ?::interval, now() - ?::interval, ?)
                """, reference, studentId, course, new BigDecimal(amount), age, age, "test:" + reference);
        return reference.toString();
    }

    private ResultActions submit(User student, String reference, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/student/transactions/{ref}/refund-requests", reference)
                .with(csrf()).with(signedIn(student)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions detail(User student, String reference) throws Exception {
        return mockMvc.perform(get("/api/v1/student/transactions/{ref}", reference).with(signedIn(student)));
    }

    private int requestCount(User student) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM refund_requests WHERE student_id = ?", Integer.class,
                studentProfileOf(student).getId());
    }
}
