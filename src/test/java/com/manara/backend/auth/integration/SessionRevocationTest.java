package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MANARA-SEC-003. What a session is still worth after the account underneath it changes.
 *
 * <p>The behaviour these tests pin down is not "the password was updated" — that already worked.
 * It is that updating it <em>reaches the sessions already open</em>. Before this change a session
 * was a snapshot taken at sign-in and never consulted again, so someone holding a stolen session
 * survived the theft victim's password reset, and an account demoted out of INSTRUCTOR kept
 * authoring for as long as its session lived.
 *
 * <p>Everything here runs against the real PostgreSQL and the real Redis session store that
 * {@link AbstractPostgresBackedTest} starts, and goes through the whole filter chain. That is
 * deliberate and it is the only way these claims mean anything: the epoch is compared between a
 * value held in a Redis-backed session and a value held in a database row, and no mock of either
 * side would be evidence about the pair.
 */
class SessionRevocationTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@revocation.example";
    private static final String EMAIL = "sara" + DOMAIN;
    private static final String PASSWORD = "sunlit harbour lantern 42";
    private static final String NEW_PASSWORD = "quiet orchard kettle 97";
    private static final String SESSION_COOKIE = "MANARA_SESSION";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * Asked for the version in force rather than hardcoded: registration refuses anything else, and
     * a bumped version should not fail a test about sessions.
     */
    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private jakarta.servlet.Filter sessionRepositoryFilter;

    @BeforeEach
    void buildMockMvcAndAccount() throws Exception {
        // Spring Session's filter is what turns a request into a Redis-backed session and puts the
        // MANARA_SESSION cookie on the response. MockMvc does not pick it up from the context the
        // way a real servlet container does, so it is added explicitly — without it these tests
        // would exercise an in-memory session and prove nothing about the store production uses.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter)
                .apply(springSecurity())
                .build();
        removeTestAccounts();

        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Sara Test","email":"%s","password":"%s","role":"STUDENT",
                                 "termsAccepted":true,"termsVersion":"%s"}
                                """.formatted(EMAIL, PASSWORD,
                                        termsVersionRegistry.current().orElseThrow().id())))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/verify-otp").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s"}
                                """.formatted(EMAIL, outstandingCode())))
                .andExpect(status().isOk());
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

    // ── The finding itself ────────────────────────────────────────────────────

    @Test
    @DisplayName("a password reset ends the sessions that were open when it happened")
    void resetEndsEveryOtherSession() throws Exception {
        Device phone = signIn(PASSWORD);
        Device laptop = signIn(PASSWORD);

        // Two genuinely separate sessions, both live. If this ever stops holding, the rest of the
        // test proves nothing, because a 401 later would not distinguish "revoked" from
        // "never signed in".
        assertThat(phone.cookie).isNotEqualTo(laptop.cookie);
        me(phone).andExpect(status().isOk());
        me(laptop).andExpect(status().isOk());

        resetPasswordByEmail(NEW_PASSWORD);

        // The reset is anonymous — nobody performing it is signed in — so no session is spared.
        me(phone).andExpect(status().isUnauthorized());
        me(laptop).andExpect(status().isUnauthorized());

        // And the credential change itself really happened, in both directions.
        signInExpecting(PASSWORD, status().isUnauthorized());
        Device fresh = signIn(NEW_PASSWORD);
        me(fresh).andExpect(status().isOk());
    }

    @Test
    @DisplayName("changing the password signs the other devices out and keeps the caller in")
    void changeKeepsTheCallerAndDropsTheRest() throws Exception {
        Device phone = signIn(PASSWORD);
        Device laptop = signIn(PASSWORD);
        me(phone).andExpect(status().isOk());
        me(laptop).andExpect(status().isOk());

        changePassword(phone, PASSWORD, NEW_PASSWORD).andExpect(status().isOk());

        // The device that made the change is re-established on a session stamped with the new
        // epoch, so it keeps working. This is the one exception, and it is an exception granted by
        // issuing a fresh session rather than by letting an old one through.
        me(phone).andExpect(status().isOk());

        // Every other device is refused on its very next request.
        me(laptop).andExpect(status().isUnauthorized());

        signInExpecting(PASSWORD, status().isUnauthorized());
    }

    @Test
    @DisplayName("a role taken away in SQL is gone from the next request, not the next sign-in")
    void outOfBandDemotionReachesAnOpenSession() throws Exception {
        jdbc.update("UPDATE users SET role = 'INSTRUCTOR' WHERE email = ?", EMAIL);

        Device session = signIn(PASSWORD);

        // The staff-only catalogue. It is authorized inside CourseService from the role carried by
        // the principal, which is the whole reason a stale principal was an authorization bug and
        // not merely stale display data — so this endpoint, and not /auth/me, is where a demotion
        // has to be observed. /auth/me re-reads the row itself and would go on looking correct
        // however wrong the session was.
        instructorCatalogue(session).andExpect(status().isOk());

        // Roles move by hand here — no application code path writes one — which is exactly why a
        // session-deletion hook could never have caught this, and why the check has to be a read.
        jdbc.update("UPDATE users SET role = 'STUDENT' WHERE email = ?", EMAIL);

        instructorCatalogue(session).andExpect(status().is4xxClientError());
    }

    // ── The edges that decide whether the fix is safe to deploy ───────────────

    @Test
    @DisplayName("a session whose epoch the row has moved past is refused")
    void aSupersededSessionIsRefused() throws Exception {
        Device session = signIn(PASSWORD);
        me(session).andExpect(status().isOk());

        // The row moves on without the application doing it — the same shape as an operator
        // revoking a session by hand, and the same comparison a password change triggers.
        jdbc.update("UPDATE users SET auth_version = auth_version + 1 WHERE email = ?", EMAIL);

        me(session).andExpect(status().isUnauthorized());

        // The sessions that predate this deploy carry no stamp at all rather than a superseded one.
        // That case is refused too, and is pinned in SessionAuthenticationFreshnessFilterTest,
        // where the session attribute can actually be manipulated — through a cookie it cannot.
    }

    @Test
    @DisplayName("a deleted account cannot keep using the session it left behind")
    void deletedAccountsAreRefused() throws Exception {
        Device session = signIn(PASSWORD);
        me(session).andExpect(status().isOk());

        removeTestAccounts();

        me(session).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the epoch is not a way around CSRF")
    void csrfStillApplies() throws Exception {
        Device session = signIn(PASSWORD);

        // Same request as the successful change above, minus the token.
        mockMvc.perform(post("/api/v1/auth/change-password")
                        .cookie(session.asCookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"%s","newPassword":"%s"}
                                """.formatted(PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isForbidden());

        // And nothing was written on the way to being refused.
        assertThat(authVersion()).isZero();
        me(session).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a failed password change disturbs nobody")
    void aRejectedChangeRevokesNothing() throws Exception {
        Device phone = signIn(PASSWORD);
        Device laptop = signIn(PASSWORD);

        changePassword(phone, "wrong-password", NEW_PASSWORD)
                .andExpect(status().is4xxClientError());

        // The epoch is only allowed to move when the password does. If a wrong guess bumped it,
        // anyone could sign every device of any account out by guessing badly on purpose.
        assertThat(authVersion()).isZero();
        me(phone).andExpect(status().isOk());
        me(laptop).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * A signed-in device, represented the way the browser represents one: by the session cookie and
     * nothing else.
     *
     * <p>Deliberately not a {@code MockHttpSession}. Sessions here live in Redis behind Spring
     * Session, so the object MockMvc would hand around is not the session the application reads —
     * carrying one would test an in-memory map and prove nothing about revocation. The cookie is
     * the real handle, and it is what a stolen session would consist of.
     */
    private static final class Device {
        private String cookie;

        private Device(String cookie) {
            this.cookie = cookie;
        }

        private Cookie asCookie() {
            return new Cookie(SESSION_COOKIE, cookie);
        }

        /** Follows the cookie if the response rotated it, as it does on sign-in and re-establish. */
        private void follow(MvcResult result) {
            Cookie issued = result.getResponse().getCookie(SESSION_COOKIE);
            if (issued != null && issued.getValue() != null && !issued.getValue().isEmpty()) {
                cookie = issued.getValue();
            }
        }
    }

    private Device signIn(String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, password)))
                .andExpect(status().isOk())
                .andReturn();

        Cookie issued = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(issued)
                .as("a successful sign-in must issue a session cookie, or this test proves nothing")
                .isNotNull();
        return new Device(issued.getValue());
    }

    private void signInExpecting(String password,
                                 org.springframework.test.web.servlet.ResultMatcher expected)
            throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, password)))
                .andExpect(expected);
    }

    private ResultActions me(Device device) throws Exception {
        return mockMvc.perform(get("/api/v1/auth/me").cookie(device.asCookie()));
    }

    private ResultActions instructorCatalogue(Device device) throws Exception {
        return mockMvc.perform(get("/api/v1/instructor/courses").cookie(device.asCookie()));
    }

    private ResultActions changePassword(Device device, String current, String replacement)
            throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/v1/auth/change-password").with(csrf())
                .cookie(device.asCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"currentPassword":"%s","newPassword":"%s"}
                        """.formatted(current, replacement)));
        device.follow(actions.andReturn());
        return actions;
    }

    /** The whole anonymous recovery flow, end to end, as a user would walk it. */
    private void resetPasswordByEmail(String newPassword) throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}
                                """.formatted(EMAIL)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/reset-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s","newPassword":"%s"}
                                """.formatted(EMAIL, outstandingCode(), newPassword)))
                .andExpect(status().isOk());
    }

    private Long authVersion() {
        return jdbc.queryForObject(
                "SELECT auth_version FROM users WHERE email = ?", Long.class, EMAIL);
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
