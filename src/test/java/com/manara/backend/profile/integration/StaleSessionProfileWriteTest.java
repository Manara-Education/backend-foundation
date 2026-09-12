package com.manara.backend.profile.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a rename is allowed to write, when the session asking for it is older than the row.
 *
 * <p>The principal in a session is a snapshot of the account taken when that session was
 * established, and it never changes again. Handing that snapshot to {@code save} is a JPA merge,
 * and a merge writes every updatable column from it — so a request carrying nothing but a display
 * name also wrote back the snapshot's password hash, role and forced-reset flag.
 *
 * <p>That turns the most harmless endpoint in the application into a way to undo the most
 * important ones. A session opened before a password recovery could restore the previous hash and
 * make the old password work again; a session opened before an operator's demotion could restore
 * the previous role. Nobody has to be attacking: an ordinary user renaming themselves at an
 * unlucky moment causes it.
 *
 * <p>Each test below therefore holds a session across an out-of-band change to the row, renames
 * through it, and then asks the <em>database</em> what happened — not the response body, which was
 * always a cheerful success either way.
 *
 * <p>The session is carried by holding on to the {@code HttpSession} the sign-in created and
 * replaying it on the later request. Not by replaying the cookie: {@code MockMvc} is built with
 * {@code springSecurity()}, which installs Spring Security's filter chain but not Spring Session's
 * {@code SessionRepositoryFilter}, so a {@code MANARA_SESSION} cookie means nothing here and every
 * request would otherwise get a new, empty session. Carrying the session object is the faithful
 * part regardless, because {@code HttpSessionSecurityContextRepository} keeps the authenticated
 * principal in a session attribute — that stored {@code User}, serialised at sign-in and never
 * refreshed, is the stale snapshot this whole test is about.
 *
 * <p>These cases stay meaningful after account-wide session revocation (MANARA-SEC-003) lands:
 * at that point the old session is rejected outright and the rename never reaches the service,
 * which satisfies every assertion here for a stronger reason. What must never happen is that they
 * pass because revocation was weakened to let the stale session through.
 */
class StaleSessionProfileWriteTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@stalesession.example";
    private static final String ORIGINAL_PASSWORD = "sunlit harbour lantern 42";
    private static final String NEW_PASSWORD = "quiet orchard kettle 97";

    private MockMvc mockMvc;

    /**
     * Asked for the version in force rather than hardcoded: registration refuses anything else, and
     * a bumped version should not fail a test about profile writes.
     */
    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

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
    @DisplayName("A rename through a pre-recovery session cannot restore the old password")
    void renameCannotRollBackARecoveredPassword() throws Exception {
        String email = "recovered" + DOMAIN;
        MockHttpSession oldSession = registerVerifyAndCaptureSession(email, "STUDENT");

        String hashBeforeRecovery = passwordHash(email);
        recoverPasswordTo(email, NEW_PASSWORD);
        String hashAfterRecovery = passwordHash(email);
        assertThat(hashAfterRecovery)
                .as("precondition: recovery must actually have changed the stored hash")
                .isNotEqualTo(hashBeforeRecovery);

        // MANARA-SEC-003 now revokes this session the moment the password changes, which is
        // exactly what it is for. Re-stamping the epoch is not a way around it: the session keeps
        // the stale principal — the pre-recovery User, old hash and all — so the write this test
        // is about is still driven by a snapshot that predates recovery. What the stamp removes is
        // only the 401 that would otherwise stop the request before it reaches the profile write,
        // which would leave field-scoping untested. Weakening revocation to keep the old session
        // usable was the alternative, and PR #60 says explicitly not to do that.
        refreshEpochStamp(oldSession, email);

        rename(oldSession, "Renamed Through Old Session");

        assertThat(passwordHash(email))
                .as("the pre-recovery session's stale hash must not have been written back")
                .isEqualTo(hashAfterRecovery);
        login(email, ORIGINAL_PASSWORD)
                .andExpect(status().isUnauthorized());
        login(email, NEW_PASSWORD)
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("A rename through a pre-demotion session cannot restore the old role")
    void renameCannotRollBackARoleCorrection() throws Exception {
        String email = "demoted" + DOMAIN;
        MockHttpSession oldSession = registerVerifyAndCaptureSession(email, "INSTRUCTOR");

        // An operator correcting a role directly on the row, which is the only mechanism that
        // exists today — there is no role-management API.
        jdbc.update("UPDATE users SET role = 'STUDENT' WHERE email = ?", email);

        rename(oldSession, "Renamed After Demotion");

        assertThat(role(email))
                .as("the pre-demotion session's stale INSTRUCTOR role must not have been restored")
                .isEqualTo("STUDENT");
    }

    @Test
    @DisplayName("A rename through a session that predates recovery cannot re-lock the account")
    void renameCannotRestoreAClearedForcedResetFlag() throws Exception {
        String email = "forcedreset" + DOMAIN;
        registerVerifyAndCaptureSession(email, "STUDENT");

        // An operator requires a password change, then the account signs in again. Sign-in is
        // allowed while the flag is set by design, so this second session's snapshot carries
        // requiresPasswordReset = true.
        jdbc.update("UPDATE users SET requires_password_reset = true WHERE email = ?", email);
        MockHttpSession flaggedSession = sessionOf(login(email, ORIGINAL_PASSWORD)
                .andExpect(status().isOk())
                .andReturn());

        // Recovery clears the flag, so the filter lets the rename through — and the rename's
        // snapshot still says the account owes a password change.
        recoverPasswordTo(email, NEW_PASSWORD);
        assertThat(requiresPasswordReset(email))
                .as("precondition: recovery must clear the forced-reset flag")
                .isFalse();

        // Recovery bumped the epoch, so this session is revoked too — see the note above.
        refreshEpochStamp(flaggedSession, email);

        rename(flaggedSession, "Renamed After Recovery");

        assertThat(requiresPasswordReset(email))
                .as("a rename must not re-impose a forced reset the account has already satisfied")
                .isFalse();
    }

    @Test
    @DisplayName("The rename itself still works, and touches nothing but the name")
    void renameStillChangesTheNameAndNothingElse() throws Exception {
        String email = "ordinary" + DOMAIN;
        MockHttpSession session = registerVerifyAndCaptureSession(email, "STUDENT");

        String hashBefore = passwordHash(email);
        rename(session, "Perfectly Ordinary Rename");

        assertThat(fullName(email)).isEqualTo("Perfectly Ordinary Rename");
        assertThat(passwordHash(email)).isEqualTo(hashBefore);
        assertThat(role(email)).isEqualTo("STUDENT");
        assertThat(emailVerified(email))
                .as("verification state must survive a rename")
                .isTrue();
    }

    // ------------------------------------------------------------ flow helpers

    /** Registers, verifies the emailed code, and returns the cookies that session is carried by. */
    private MockHttpSession registerVerifyAndCaptureSession(String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Test Person","email":"%s","password":"%s","role":"%s",
                                 "termsAccepted":true,"termsVersion":"%s"}"""
                                .formatted(email, ORIGINAL_PASSWORD, role,
                                        termsVersionRegistry.current().orElseThrow().id())))
                .andExpect(status().isCreated());

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/verify-otp").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s"}"""
                                .formatted(email, outstandingCode(email))))
                .andExpect(status().isOk())
                .andReturn();

        return sessionOf(verified);
    }

    /** The anonymous forgot-password flow, run to completion. */
    private void recoverPasswordTo(String email, String newPassword) throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s"}""".formatted(email)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/reset-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s","newPassword":"%s"}"""
                                .formatted(email, outstandingCode(email), newPassword)))
                .andExpect(status().isOk());
    }

    private void rename(MockHttpSession session, String newName) throws Exception {
        mockMvc.perform(put("/api/v1/profile").with(csrf())
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"%s"}""".formatted(newName)))
                .andExpect(status().isOk());
    }

    /**
     * Brings the session's authentication epoch up to the account's current one, leaving everything
     * else about the session — above all its stale principal — untouched.
     *
     * <p>Reads the epoch from the row rather than incrementing a counter, so the stamp is whatever
     * the account actually carries now. A test whose account has since had another credential
     * change still gets the 401 it should.
     */
    private void refreshEpochStamp(MockHttpSession session, String email) {
        Long current = jdbc.queryForObject(
                "SELECT auth_version FROM users WHERE email = ?", Long.class, email);
        session.setAttribute(SessionManager.AUTH_VERSION_ATTRIBUTE, current);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}""".formatted(email, password)));
    }

    /**
     * The session the request ended up authenticated against.
     *
     * <p>Read off the request rather than the response, because sign-in replaces the session and
     * rotates its id: the interesting session is the one that exists after
     * {@code HttpSessionManager.establish} has saved the security context into it.
     */
    private static MockHttpSession sessionOf(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session)
                .as("sign-in must have established a session holding the security context")
                .isNotNull();
        assertThat(session.getAttribute("SPRING_SECURITY_CONTEXT"))
                .as("the established session must carry the authenticated principal")
                .isNotNull();
        return session;
    }

    // ------------------------------------------------------------ database helpers

    private String passwordHash(String email) {
        return jdbc.queryForObject("SELECT password FROM users WHERE email = ?", String.class, email);
    }

    private String role(String email) {
        return jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email);
    }

    private String fullName(String email) {
        return jdbc.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, email);
    }

    private Boolean requiresPasswordReset(String email) {
        return jdbc.queryForObject(
                "SELECT requires_password_reset FROM users WHERE email = ?", Boolean.class, email);
    }

    private Boolean emailVerified(String email) {
        return jdbc.queryForObject(
                "SELECT email_verified FROM users WHERE email = ?", Boolean.class, email);
    }

    /** The code the application generated and would have emailed. Never exposed by any API. */
    private String outstandingCode(String email) {
        return jdbc.queryForObject("""
                SELECT o.code FROM otps o
                  JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.used = false
                 ORDER BY o.created_at DESC
                 LIMIT 1
                """, String.class, email);
    }
}
