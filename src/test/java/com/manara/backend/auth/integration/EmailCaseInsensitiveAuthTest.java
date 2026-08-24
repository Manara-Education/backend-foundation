package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The behaviour a user actually notices, driven end to end through the HTTP API.
 *
 * <p>{@code Ali@x.com} and {@code ali@x.com} used to be two accounts. Someone could register the
 * differently-cased twin of an address that already existed; the owner of the original would then
 * receive verification codes for an account that was not theirs, and a user who capitalised their
 * address one day and not the next would be told no such account existed.
 *
 * <p>Each step below deliberately uses a <em>different</em> casing from the one before it, so the
 * account is registered, verified, and signed in to under three spellings and one padded form. If
 * any layer — the request DTO, the mapper, the repository, the OTP join, the authentication
 * provider — still compared addresses literally, one of these steps would fail.
 */
class EmailCaseInsensitiveAuthTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@authcase.example";
    private static final String CANONICAL = "ali" + DOMAIN;
    private static final String PASSWORD = "password123";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    /**
     * Built by hand rather than with {@code @AutoConfigureMockMvc}: Spring Boot 4 moved that
     * annotation into {@code spring-boot-webmvc-test}, and pulling in a module for one annotation
     * is not worth it. {@code springSecurity()} installs the real filter chain, so these requests
     * pass through CSRF and authentication exactly as a browser's would.
     */
    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void removeTestAccounts() {
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM instructors WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    @DisplayName("register padded and shouted, verify in a third casing, sign in in a fourth")
    void oneAccountAcrossEveryCasing() throws Exception {
        // Registered with both surrounding whitespace and mixed case. The padding matters: left
        // untrimmed it is not a valid address at all, and @Email would reject the request before
        // any of this could be reached.
        register("  Ali@authcase.example  ").andExpect(status().isCreated());

        assertThat(storedEmail())
                .as("the row must hold the canonical address regardless of how it was typed")
                .isEqualTo(CANONICAL);

        // Verification, in a casing the account was never registered under. This exercises the
        // OTP lookup, which joins otps to users on the stored address.
        verifyOtp("ALI@AUTHCASE.EXAMPLE", outstandingCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.email").value(CANONICAL));

        // Sign-in, in yet another casing. This goes through the AuthenticationManager and the
        // UserDetailsService, not just the repository.
        login("aLi@AuthCase.Example")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.email").value(CANONICAL));
    }

    @Test
    @DisplayName("registering the same address in another casing is rejected as a duplicate")
    void caseVariantRegistrationIsRejected() throws Exception {
        register("Ali@authcase.example").andExpect(status().isCreated());

        // The project's standard duplicate-account response — not a 500, and not a PostgreSQL
        // constraint message reaching the client.
        register("ali@authcase.example")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[0]").value("Email is already registered"));

        register("ALI@AUTHCASE.EXAMPLE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value("Email is already registered"));

        assertThat(accountCount())
                .as("a case variant must not have created a second account")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("no PostgreSQL constraint detail ever reaches the client")
    void duplicateResponseLeaksNothing() throws Exception {
        register("Ali@authcase.example").andExpect(status().isCreated());

        String body = register("ali@authcase.example")
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("uk_users_email_lower")
                .doesNotContain("ConstraintViolation")
                .doesNotContain("duplicate key value");
    }

    @Test
    @DisplayName("the repository finds the registered account under any casing")
    void repositoryResolvesEveryCasing() throws Exception {
        register("Ali@authcase.example").andExpect(status().isCreated());

        for (String variant : new String[]{
                CANONICAL, "Ali" + DOMAIN, "ALI@AUTHCASE.EXAMPLE", "  aLi@AuthCase.Example  "}) {
            assertThat(userRepository.findByEmail(variant))
                    .as("findByEmail(\"%s\")", variant)
                    .isPresent();
        }
    }

    // ------------------------------------------------------------ request helpers

    private ResultActions register(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Ali Test","email":"%s","password":"%s","role":"STUDENT"}
                        """.formatted(email, PASSWORD)));
    }

    private ResultActions verifyOtp(String email, String code)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/verify-otp").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","code":"%s"}
                        """.formatted(email, code)));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, PASSWORD)));
    }

    // ------------------------------------------------------------ database helpers

    private String storedEmail() {
        return jdbc.queryForObject(
                "SELECT email FROM users WHERE email LIKE ?", String.class, "%" + DOMAIN);
    }

    private Integer accountCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email LIKE ?", Integer.class, "%" + DOMAIN);
    }

    /** The code the application generated and would have emailed. Never exposed by any API. */
    private String outstandingCode() {
        return jdbc.queryForObject("""
                SELECT o.code FROM otps o
                  JOIN users u ON u.id = o.user_id
                 WHERE u.email LIKE ? AND o.used = false
                 ORDER BY o.created_at DESC
                 LIMIT 1
                """, String.class, "%" + DOMAIN);
    }
}
