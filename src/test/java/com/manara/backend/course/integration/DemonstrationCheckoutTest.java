package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CoursePurchase;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.course.repository.CoursePurchaseRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-D01. What checkout does in a demonstration deployment, spoken over HTTP.
 *
 * <p>The pentest of 2026-09-10 sent {@code {"paymentMethod":{},"amount":0,"price":0}} for a course
 * priced 499 and was handed a {@code sim_} receipt and the paid content. The price rule held — the
 * server charged its own 499.00 — but no payment happened, because the deployment was a
 * demonstration without having said so. Production now has to say so ({@code CommerceModeTest}).
 * What must stay true inside a demonstration is asserted here: whatever the body claims, the stored
 * price and currency are what is recorded; a repeat charges nothing; and the response says the grant
 * came from a simulated payment, so a client never has to present it as a sale.
 *
 * <p>Each test asserts the database before the new {@code simulated} field, so against the code
 * before that field existed the only failure is its absence — the price rule was already correct.
 */
class DemonstrationCheckoutTest extends AbstractCourseAuthoringTest {

    private static final String CHECKOUT = "/api/v1/student/courses/{id}/checkout";

    /**
     * Every payment-shaped field a client could invent. {@code CheckoutRequest} declares none of
     * them, so every one must be dropped rather than read.
     */
    private static final String FORGED_PAYMENT_FIELDS = """
            "amount": 0, "price": 0, "purchasePrice": 0, "listPrice": 0, "amountPaid": 0,
            "currency": "USD", "status": "PAID", "paymentStatus": "SUCCEEDED", "paid": true,
            "receipt": "rcpt_forged", "paymentReference": "live_forged", "simulated": false""";

    @Autowired WebApplicationContext context;
    @Autowired CoursePurchaseRepository coursePurchaseRepository;
    @Autowired CourseSubscriptionRepository courseSubscriptionRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("the full application in this context is a demonstration, served by the simulator")
    void thisContextIsADemonstration() {
        assertThat(context.getBean(com.manara.backend.payment.config.CommerceMode.class).name())
                .isEqualTo("DEMONSTRATION");
        assertThat(context.getBean(com.manara.backend.payment.service.PaymentGateway.class))
                .isInstanceOf(com.manara.backend.payment.service.SimulatedPaymentGateway.class);
    }

    @Test
    @DisplayName("forged amount, price, currency and status buy nothing: the stored 499.00 EGP is recorded")
    void forgedPaymentFieldsAreIgnored() throws Exception {
        Long courseId = purchaseCourse("499.00").getId();
        User learner = newStudentUser();

        MvcResult result = checkout(learner, courseId,
                "{\"paymentMethod\": {}, " + FORGED_PAYMENT_FIELDS + "}");

        List<CoursePurchase> purchases = purchasesOf(learner, courseId);
        assertThat(purchases).hasSize(1);
        CoursePurchase purchase = purchases.getFirst();
        assertThat(purchase.getListPrice()).isEqualByComparingTo("499.00");
        assertThat(purchase.getAmountPaid()).isEqualByComparingTo("499.00");
        assertThat(purchase.getCurrency()).isEqualTo("EGP");
        assertThat(purchase.getPaymentReference()).startsWith("sim_");

        jsonPath("$.data.paymentReference", is(purchase.getPaymentReference())).match(result);
        jsonPath("$.data.access.entitled", is(true)).match(result);
        jsonPath("$.data.simulated", is(true)).match(result);
    }

    @Test
    @DisplayName("checking out twice grants once and charges once")
    void aRepeatedCheckoutChargesNothingTwice() throws Exception {
        Long courseId = purchaseCourse("499.00").getId();
        User learner = newStudentUser();
        String body = "{\"paymentMethod\": {}, " + FORGED_PAYMENT_FIELDS + "}";

        checkout(learner, courseId, body);
        MvcResult repeat = checkout(learner, courseId, body);

        Long studentId = studentProfileOf(learner).getId();
        assertThat(purchasesOf(learner, courseId)).hasSize(1);
        assertThat(rowsFor("course_entitlements", courseId, studentId)).isEqualTo(1);
        assertThat(rowsFor("enrollments", courseId, studentId)).isEqualTo(1);
        assertThat(courseEntitlementRepository.findByCourseIdAndStudentId(courseId, studentId)).get()
                .satisfies(entitlement -> assertThat(entitlement.getSource()).isEqualTo(EntitlementSource.PURCHASE));

        // The repeat charged nothing, so it has no receipt — and nothing to call simulated.
        jsonPath("$.data.access.entitled", is(true)).match(repeat);
        jsonPath("$.data.paymentReference", is(nullValue())).match(repeat);
        jsonPath("$.data.simulated", is(false)).match(repeat);
    }

    @Test
    @DisplayName("a demonstration subscription records the plan's own price and is labelled simulated")
    void aDemonstrationSubscriptionIsLabelledSimulated() throws Exception {
        var request = flatCourse("Subscribed", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.SUBSCRIPTION);
        request.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00")));
        InstructorCourseResponse course = courseService.createCourse(instructorUser, request);
        Long planId = course.getSubscriptionPlans().getFirst().getId();
        User learner = newStudentUser();

        MvcResult result = checkout(learner, course.getId(),
                "{\"planId\": " + planId + ", \"paymentMethod\": {}, " + FORGED_PAYMENT_FIELDS + "}");

        var subscription = courseSubscriptionRepository
                .findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(course.getId(), studentProfileOf(learner).getId())
                .orElseThrow();
        assertThat(subscription.getPricePaid()).isEqualByComparingTo("100.00");
        assertThat(subscription.getPaymentReference()).startsWith("sim_");

        jsonPath("$.data.access.entitled", is(true)).match(result);
        jsonPath("$.data.paymentReference", startsWith("sim_")).match(result);
        jsonPath("$.data.simulated", is(true)).match(result);
    }

    @Test
    @DisplayName("a free course takes no payment, is not labelled simulated, and still enrols")
    void aFreeCourseIsNotSimulatedAndStillEnrols() throws Exception {
        var request = flatCourse("Free", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.FREE);
        Long courseId = courseService.createCourse(instructorUser, request).getId();
        User learner = newStudentUser();

        MvcResult result = checkout(learner, courseId, "{}");

        Long studentId = studentProfileOf(learner).getId();
        assertThat(rowsFor("enrollments", courseId, studentId)).isEqualTo(1);
        assertThat(purchasesOf(learner, courseId)).isEmpty();
        assertThat(courseEntitlementRepository.findByCourseIdAndStudentId(courseId, studentId)).get()
                .satisfies(entitlement -> assertThat(entitlement.getSource()).isEqualTo(EntitlementSource.FREE));

        jsonPath("$.data.access.entitled", is(true)).match(result);
        jsonPath("$.data.paymentReference", is(nullValue())).match(result);
        jsonPath("$.data.simulated", is(false)).match(result);
    }

    // --- fixtures ------------------------------------------------------------

    private MvcResult checkout(User learner, Long courseId, String body) throws Exception {
        return mockMvc.perform(post(CHECKOUT, courseId)
                        .with(signedIn(learner)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
    }

    private InstructorCourseResponse purchaseCourse(String price) {
        var request = flatCourse("For sale", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.PURCHASE);
        request.setPurchasePrice(new BigDecimal(price));
        return courseService.createCourse(instructorUser, request);
    }

    private List<CoursePurchase> purchasesOf(User learner, Long courseId) {
        return coursePurchaseRepository.findByCourseIdAndStudentIdOrderByPurchasedAtAsc(
                courseId, studentProfileOf(learner).getId());
    }

    private int rowsFor(String table, Long courseId, Long studentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE course_id = ? AND student_id = ?",
                Integer.class, courseId, studentId);
    }
}
