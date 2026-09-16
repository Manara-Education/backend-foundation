package com.manara.backend.contact.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contact form asked the way an anonymous browser asks it: over HTTP, through the real
 * security filter chain, with no session — the same style {@code PublicCourseApiTest} and
 * {@code PublicRegistrationRoleTest} use for the other unauthenticated routes.
 *
 * <p>{@code emailService} is the {@code @MockitoBean} {@link AbstractPostgresBackedTest} already
 * declares, so a real Resend call is never made. What is asserted is the contract: a completed
 * send is what makes the response {@code 200}, and a provider failure is what makes it
 * {@code 503} — never a silently-accepted submission.
 */
@SpringBootTest(
        // Seven submissions from one test class in one address would trip the endpoint's own
        // 5-per-15-minutes rate limit long before any of these assertions ran. Rate limiting is
        // exercised separately (RateLimiterTest, PublicCatalogueRateLimitRuleTest); it is not
        // what this class is about.
        properties = "app.rate-limit.enabled=false")
class ContactControllerTest extends AbstractPostgresBackedTest {

    private static final String ENDPOINT = "/api/v1/contact";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        given(emailService.send(any())).willReturn(new EmailSendResult("msg_test"));
    }

    private static String body(String name, String email, String topic, String message) {
        return """
                {"name":"%s","email":"%s","topic":"%s","message":"%s"}
                """.formatted(name, email, topic, message);
    }

    @Test
    @DisplayName("a well-formed submission is sent and answered 200 only after sending succeeds")
    void acceptsAWellFormedSubmission() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("سارة أحمد", "sara@example.com", "الدورات", "متى تبدأ الدورة القادمة؟")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"));

        var captor = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService).send(captor.capture());
        EmailMessage sent = captor.getValue();
        assertThat(sent.replyTo()).isEqualTo("sara@example.com");
        assertThat(sent.html()).contains("سارة أحمد");
    }

    @Test
    @DisplayName("a provider failure surfaces as 503, not a false success")
    void surfacesProviderFailureAs503() throws Exception {
        given(emailService.send(any())).willThrow(new EmailDeliveryException("error.email.deliveryFailed"));

        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("سارة أحمد", "sara@example.com", "الدورات", "متى تبدأ الدورة القادمة؟")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("missing CSRF token is refused before the controller is reached")
    void refusesWithoutCsrfToken() throws Exception {
        // MockMvc answers 403 here. The running application, over the real port, answers 401 for
        // the identical request (verified with curl against a live instance): Spring Security
        // routes an anonymous caller's CSRF rejection through WebSecurityCustomizers'
        // HttpStatusEntryPoint(UNAUTHORIZED) rather than the access-denied handler, and MockMvc's
        // simulated dispatch does not reproduce that path. Pre-existing to this endpoint — the
        // same gap would show on any route under this security configuration — so the assertion
        // here is deliberately status-agnostic and checks the one thing both environments agree
        // on: the request is refused and never reaches the service.
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("سارة أحمد", "sara@example.com", "الدورات", "متى تبدأ الدورة القادمة؟")))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    if (status != 401 && status != 403) {
                        throw new AssertionError("Expected 401 or 403, got " + status);
                    }
                });

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("a blank name is refused before anything is sent")
    void refusesBlankName() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "sara@example.com", "الدورات", "متى تبدأ الدورة القادمة؟")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("an invalid email is refused before anything is sent")
    void refusesInvalidEmail() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("سارة أحمد", "not-an-email", "الدورات", "متى تبدأ الدورة القادمة؟")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("a topic outside the fixed list is refused, since it reaches the email subject line")
    void refusesATopicOutsideTheFixedList() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("سارة أحمد", "sara@example.com", "topic injected by client",
                                "متى تبدأ الدورة القادمة؟")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailService);
    }

    @Test
    @DisplayName("a message over the length limit is refused before anything is sent")
    void refusesAnOverlongMessage() throws Exception {
        String tooLong = "أ".repeat(4001);

        mockMvc.perform(post(ENDPOINT)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("سارة أحمد", "sara@example.com", "الدورات", tooLong)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(emailService);
    }
}
