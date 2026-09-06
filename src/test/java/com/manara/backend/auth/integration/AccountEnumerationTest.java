package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * MANARA-SEC-006. Whether a stranger can find out who has an account here.
 *
 * <p>Each test asks the same question of an address that exists and an address that does not, and
 * compares the two answers as a whole — status, envelope and text together. A difference anywhere in
 * that triple is a membership test on the platform, available to anyone who can reach the endpoint,
 * one address at a time.
 *
 * <p>The comparison is written as an assertion on the pair rather than as two separate expectations
 * on literal values on purpose. A test that says "both return 200" goes on passing when someone
 * changes both branches to return different bodies; this one does not.
 */
class AccountEnumerationTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@enumeration.example";
    private static final String REGISTERED = "exists" + DOMAIN;
    private static final String UNKNOWN = "nobody" + DOMAIN;
    private static final String PASSWORD = "password123";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void buildMockMvcAndAccount() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        removeTestAccounts();

        given(emailService.send(any())).willReturn(new EmailSendResult("stub"));

        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Exists Test","email":"%s","password":"%s","role":"STUDENT"}
                        """.formatted(REGISTERED, PASSWORD)));
    }

    @AfterEach
    void removeTestAccounts() {
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ── The oracles the report found ──────────────────────────────────────────

    @Test
    @DisplayName("forgot-password answers a registered and an unknown address identically")
    void forgotPasswordIsUniform() throws Exception {
        assertThat(forgotPassword(REGISTERED))
                .as("a registered and an unknown address must be indistinguishable here")
                .isEqualTo(forgotPassword(UNKNOWN));
    }

    @Test
    @DisplayName("resend-otp answers all three of its cases identically")
    void resendOtpIsUniform() throws Exception {
        // This endpoint was a three-way oracle, not a two-way one: 404 said no account existed,
        // 400 "already verified" said one existed and was confirmed, and 200 said one existed and
        // was not. All three arms are compared, so closing two of them is not enough to pass.
        String unknown = resendOtp(UNKNOWN);
        String unverified = resendOtp(REGISTERED);

        jdbc.update("UPDATE users SET email_verified = true WHERE email = ?", REGISTERED);
        String verified = resendOtp(REGISTERED);

        assertThat(List.of(unknown, unverified, verified))
                .as("no account, unverified account and verified account must be indistinguishable")
                .containsOnly(unknown);
    }

    @Test
    @DisplayName("the reset code step does not re-derive what forgot-password stopped disclosing")
    void theOtpStepIsUniform() throws Exception {
        // Without this, the fix above is worth nothing: an attacker takes the uniform 200 from
        // forgot-password and immediately asks verify-reset-otp for a code. "No outstanding code"
        // is only ever true for an address with no account; "wrong code" proves one exists.
        //
        // The registered account is walked through forgot-password first, so it genuinely has an
        // outstanding code and the two cases genuinely differ underneath. Comparing them without
        // that step compares two "no outstanding code" answers, which are identical however this
        // is implemented -- a test that passes against the unfixed service and proves nothing.
        given(emailService.send(any())).willReturn(new EmailSendResult("stub"));
        forgotPassword(REGISTERED);

        assertThat(verifyResetOtp(UNKNOWN, "000000"))
                .as("a wrong code and no account at all must look the same")
                .isEqualTo(verifyResetOtp(REGISTERED, "000000"));
    }

    // ── The oracles that survive a naive fix ──────────────────────────────────

    @Test
    @DisplayName("a mail provider outage does not answer existing accounts differently")
    void aProviderOutageIsNotAnOracle() throws Exception {
        // The oracle that outlives unifying status codes, and shows up exactly when nobody is
        // watching: sending inline meant a provider failure produced a 503 for addresses that have
        // an account and an ordinary success for addresses that do not.
        given(emailService.send(any()))
                .willThrow(new EmailDeliveryException("error.email.deliveryFailed"));

        assertThat(forgotPassword(REGISTERED))
                .as("an outage must not distinguish a registered address from an unknown one")
                .isEqualTo(forgotPassword(UNKNOWN));
    }

    @Test
    @DisplayName("a code is only ever sent to the address that owns it")
    void mailGoesOnlyToTheRealAccount() throws Exception {
        var sent = org.mockito.ArgumentCaptor.forClass(EmailMessage.class);

        forgotPassword(UNKNOWN);
        forgotPassword(REGISTERED);

        // Uniform answers must not have been bought by sending mail for addresses that have no
        // account -- that would be worse than the disclosure it replaced.
        //
        // Dispatch happens after commit and off the request thread, so this waits for the send
        // rather than checking immediately: an assertion that ran before the executor did would
        // pass by finding nothing, which is the one result that proves nothing.
        org.mockito.Mockito.verify(emailService, org.mockito.Mockito.timeout(5_000).atLeastOnce())
                .send(sent.capture());
        assertThat(sent.getAllValues())
                .as("the registered address must actually have been sent something")
                .isNotEmpty()
                .as("nothing may be sent on behalf of an address with no account")
                .allSatisfy(message -> assertThat(message.to()).isEqualTo(REGISTERED));
    }

    @Test
    @DisplayName("recovery still works for the account that asked for it")
    void recoveryStillWorks() throws Exception {
        given(emailService.send(any())).willReturn(new EmailSendResult("stub"));

        forgotPassword(REGISTERED);

        String code = jdbc.queryForObject("""
                SELECT o.code FROM otps o
                  JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.used = false AND o.type = 'PASSWORD_RESET'
                 ORDER BY o.created_at DESC LIMIT 1
                """, String.class, REGISTERED);

        assertThat(code).as("a real account must still receive a real code").isNotNull();

        // And the code works, so uniformity was not bought by breaking the flow it protects.
        assertThat(verifyResetOtp(REGISTERED, code)).startsWith("200");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The whole externally visible answer as one string: status, then body. Compared as a unit
     * because an oracle can hide in either half, and in the relationship between them.
     */
    private String answerOf(MvcResult result) throws Exception {
        return result.getResponse().getStatus()
                + " " + result.getResponse().getContentAsString();
    }

    private String forgotPassword(String email) throws Exception {
        return answerOf(mockMvc.perform(post("/api/v1/auth/forgot-password").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s"}
                        """.formatted(email))).andReturn());
    }

    private String resendOtp(String email) throws Exception {
        return answerOf(mockMvc.perform(post("/api/v1/auth/resend-otp").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","type":"EMAIL_VERIFICATION"}
                        """.formatted(email))).andReturn());
    }

    private String verifyResetOtp(String email, String code) throws Exception {
        return answerOf(mockMvc.perform(post("/api/v1/auth/verify-reset-otp").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","code":"%s"}
                        """.formatted(email, code))).andReturn());
    }
}
