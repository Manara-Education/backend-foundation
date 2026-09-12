package com.manara.backend.course.integration;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.payment.config.CommerceMode;
import com.manara.backend.payment.service.SimulatedPaymentGateway;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-D01. FREE_ONLY as the full application serves it: the mode for a production deployment with no
 * payment provider.
 *
 * <p>It exists because the other two modes each fail such a deployment. DEMONSTRATION stays up by
 * unlocking paid courses against simulated receipts; LIVE refuses to start. FREE_ONLY stays up and
 * does not sell: a free course enrols exactly as before, and a paid checkout is turned away before
 * anything is written, so the learner holds nothing and the paid lesson stays locked.
 *
 * <p>A context of its own, because the mode is read once, at startup.
 */
@TestPropertySource(properties = "manara.commerce.mode=FREE_ONLY")
class FreeOnlyCheckoutTest extends AbstractCourseAuthoringTest {

    private static final String CHECKOUT = "/api/v1/student/courses/{id}/checkout";
    private static final String LESSON = "/api/v1/student/courses/{courseId}/lessons/{lessonId}";

    /** The fixture lesson's video id — the paid content that must not be served. */
    private static final String PAID_CONTENT = "dQw4w9WgXcQ";

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("the application starts in FREE_ONLY with no payment provider and no simulator")
    void startsWithNoProviderAndNoSimulator() {
        assertThat(context.getBean(CommerceMode.class).name()).isEqualTo("FREE_ONLY");
        assertThat(context.getBeansOfType(SimulatedPaymentGateway.class)).isEmpty();
    }

    @Test
    @DisplayName("a free course enrols exactly as before")
    void aFreeCourseStillEnrols() throws Exception {
        var request = flatCourse("Free", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.FREE);
        Long courseId = courseService.createCourse(instructorUser, request).getId();
        User learner = newStudentUser();

        checkout(learner, courseId, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access.entitled", is(true)))
                .andExpect(jsonPath("$.data.paymentReference", is(nullValue())))
                .andExpect(jsonPath("$.data.simulated", is(false)));

        Long studentId = studentProfileOf(learner).getId();
        assertThat(rowsFor("enrollments", courseId, studentId)).isEqualTo(1);
        assertThat(rowsFor("course_entitlements", courseId, studentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a purchase is refused before anything is written, and the paid lesson stays locked")
    void aPurchaseIsRefusedAndWritesNothing() throws Exception {
        var request = flatCourse("For sale", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.PURCHASE);
        request.setPurchasePrice(new BigDecimal("499.00"));
        var course = courseService.createCourse(instructorUser, request);
        User learner = newStudentUser();

        refused(checkout(learner, course.getId(), "{\"paymentMethod\": {}}"));

        assertNothingWritten(learner, course.getId());
        assertLessonLocked(learner, course.getId(), course.getLessons().getFirst().getId());
    }

    @Test
    @DisplayName("a subscription is refused before anything is written, and the paid lesson stays locked")
    void aSubscriptionIsRefusedAndWritesNothing() throws Exception {
        var request = flatCourse("Subscribed", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.SUBSCRIPTION);
        request.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00")));
        var course = courseService.createCourse(instructorUser, request);
        Long planId = course.getSubscriptionPlans().getFirst().getId();
        User learner = newStudentUser();

        refused(checkout(learner, course.getId(), "{\"planId\": " + planId + ", \"paymentMethod\": {}}"));

        assertNothingWritten(learner, course.getId());
        assertLessonLocked(learner, course.getId(), course.getLessons().getFirst().getId());
    }

    // --- fixtures ------------------------------------------------------------

    private ResultActions checkout(User learner, Long courseId, String body) throws Exception {
        return mockMvc.perform(post(CHECKOUT, courseId)
                .with(signedIn(learner)).with(csrf())
                .header("Accept-Language", "en")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Refused by name, so a client can say "paid courses are not on sale" rather than show an error. */
    private void refused(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("PAYMENTS_UNAVAILABLE")))
                .andExpect(jsonPath("$.errors[0]", is("Paid checkout is not available at the moment.")));
    }

    private void assertNothingWritten(User learner, Long courseId) {
        Long studentId = studentProfileOf(learner).getId();
        for (String table : List.of("course_purchases", "course_subscriptions", "course_entitlements", "enrollments")) {
            assertThat(rowsFor(table, courseId, studentId)).as("rows in %s", table).isZero();
        }
    }

    private void assertLessonLocked(User learner, Long courseId, Long lessonId) throws Exception {
        String body = mockMvc.perform(get(LESSON, courseId, lessonId).with(signedIn(learner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"locked\":true").doesNotContain(PAID_CONTENT);
    }

    private int rowsFor(String table, Long courseId, Long studentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE course_id = ? AND student_id = ?",
                Integer.class, courseId, studentId);
    }
}
