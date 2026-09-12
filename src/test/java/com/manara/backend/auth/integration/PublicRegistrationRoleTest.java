package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.service.TermsVersionRegistry;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a stranger is allowed to make themselves, asked of the running application.
 *
 * <p>Registration is public, and the request body carried a {@code role} that was written to the
 * account verbatim. Anyone who could reach the endpoint could therefore hand themselves ADMIN,
 * confirm the address from a mailbox they owned, and sign in as staff — which, among other things,
 * opens the platform-wide catalogue of every instructor's draft and private courses. An emailed
 * code proves someone owns a mailbox. It does not make them staff.
 *
 * <p>Driven through the real filter chain against a real PostgreSQL rather than a mocked service,
 * because the claim being made is about rows: not merely that the call returns 400, but that
 * nothing was written before it did. A refusal that still leaves a user, a profile or an OTP behind
 * is not a refusal. The three assertions after the rejected request are the actual finding.
 *
 * <p>The two accepted cases matter as much as the rejected one. STUDENT is what the sign-up screen
 * sends (it omits the field entirely), and INSTRUCTOR is — today — the only way an instructor
 * account can come into existence at all: no sign-up screen offers it, no endpoint provisions it,
 * no seeder creates it. A fix that closed the endpoint to everything but STUDENT would have shut
 * the only door instructors have, so both are pinned here against exactly that regression.
 */
class PublicRegistrationRoleTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@registrationrole.example";
    private static final String PASSWORD = "Sunlit harbour lantern 42!";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    /**
     * Asked for the version in force rather than hardcoded, because registration now refuses
     * anything else. Without it the two accepted cases below would be rejected by terms validation
     * and the refused one would return 400 for a reason that has nothing to do with its role —
     * passing while proving nothing.
     */
    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void removeTestAccounts() {
        jdbc.update("DELETE FROM terms_acceptances WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM instructors WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    @DisplayName("A registration asking for ADMIN is refused, and leaves nothing behind")
    void refusesAdminRegistrationWithoutSideEffects() throws Exception {
        String email = "wants-admin" + DOMAIN;

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Wants Admin", email, "\"ADMIN\"")))
                .andExpect(status().isBadRequest());

        assertThat(userRepository.findByEmail(email))
                .as("no account may exist for a refused privileged registration")
                .isEmpty();
        assertThat(countIn("otps", email))
                .as("no OTP may be issued for a refused privileged registration")
                .isZero();
        // @MockitoBean is reset between test methods, so the only send that could be recorded here
        // is one this request made.
        verify(emailService, never()).send(any());
    }

    @Test
    @DisplayName("The sign-up screen's request — no role at all — still creates a student")
    void stillRegistersAStudentWhenNoRoleIsSupplied() throws Exception {
        String email = "plain-signup" + DOMAIN;

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithoutRole("Plain Signup", email)))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByEmail(email))
                .get()
                .satisfies(user -> assertThat(user.getRole()).isEqualTo(Role.STUDENT));
        assertThat(countIn("students", email))
                .as("a student registration still creates its student profile")
                .isOne();
    }

    @Test
    @DisplayName("Instructor onboarding, the only one that exists, still works")
    void stillRegistersAnInstructor() throws Exception {
        String email = "instructor-signup" + DOMAIN;

        mockMvc.perform(post("/api/v1/auth/register")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Instructor Signup", email, "\"INSTRUCTOR\"")))
                .andExpect(status().isCreated());

        assertThat(userRepository.findByEmail(email))
                .get()
                .satisfies(user -> assertThat(user.getRole()).isEqualTo(Role.INSTRUCTOR));
        assertThat(countIn("instructors", email))
                .as("an instructor registration still creates its instructor profile")
                .isOne();
    }

    private Integer countIn(String table, String email) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE user_id IN (SELECT id FROM users WHERE email = ?)",
                Integer.class, email);
    }

    private String body(String fullName, String email, String roleLiteral) {
        return """
                {"fullName":"%s","email":"%s","password":"%s","role":%s,
                 "termsAccepted":true,"termsVersion":"%s"}"""
                .formatted(fullName, email, PASSWORD, roleLiteral, currentTermsVersion());
    }

    private String bodyWithoutRole(String fullName, String email) {
        return """
                {"fullName":"%s","email":"%s","password":"%s",
                 "termsAccepted":true,"termsVersion":"%s"}"""
                .formatted(fullName, email, PASSWORD, currentTermsVersion());
    }

    private String currentTermsVersion() {
        return termsVersionRegistry.current().orElseThrow().id();
    }
}
