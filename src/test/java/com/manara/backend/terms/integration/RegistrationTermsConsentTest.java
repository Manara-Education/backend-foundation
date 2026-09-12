package com.manara.backend.terms.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Consent is taken at the one door into the application, or nobody comes in.
 *
 * <p>{@code AuthService#register} is the only method that creates a {@code User} — there is no
 * social sign-up, no invitation, no admin-created account — so requiring consent there requires it
 * everywhere. These tests drive the real HTTP endpoint through the real filter chain and then look
 * at the database, because the interesting property is not what the response said but what was left
 * behind.
 *
 * <h2>Why every rejection asserts five separate absences</h2>
 * "Refused with a 400" is not the guarantee. The guarantee is that a refused registration creates
 * <em>nothing</em>: no account, no student or instructor profile, no consent row, no OTP, and no
 * email to somebody who never finished signing up. Each of those is a different way for the check to
 * be in the wrong place — after the user is saved, after the profile is written, after the code is
 * generated — and each would pass a test that only looked at the status code. So every rejection
 * below compares row counts taken before the request with the counts after it, across all four
 * tables at once, and verifies that the mail provider was never asked to send anything.
 *
 * <p>Counts are deltas rather than absolutes because the PostgreSQL container is shared by every
 * test class in the JVM: another class's rows may already be there, and what matters is that this
 * request added none.
 */
class RegistrationTermsConsentTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@termsconsent.example";
    private static final String EMAIL = "consent" + DOMAIN;
    private static final String PASSWORD = "sunlit harbour lantern 42";

    /** Every table a registration writes to. Nothing here may move when one is refused. */
    private static final List<String> WRITTEN_BY_REGISTRATION =
            List.of("users", "students", "instructors", "otps", "terms_acceptances");

    private MockMvc mockMvc;
    private Map<String, Integer> before;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TermsVersionRegistry registry;

    @BeforeEach
    void buildMockMvcAndTakeBaseline() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        before = rowCounts();
    }

    @AfterEach
    void removeTestAccounts() {
        // terms_acceptances is ON DELETE CASCADE from users, so deleting the account takes its
        // consent record with it. Named here anyway: a cleanup that silently depends on a cascade
        // leaves rows behind the day the constraint changes.
        jdbc.update("DELETE FROM terms_acceptances WHERE user_id IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", "%" + DOMAIN);
        jdbc.update("DELETE FROM otps WHERE user_id IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", "%" + DOMAIN);
        jdbc.update("DELETE FROM instructors WHERE user_id IN "
                + "(SELECT id FROM users WHERE email LIKE ?)", "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ── The one shape that is accepted ────────────────────────────────────────

    @Test
    @DisplayName("an explicit true and the version in force creates the account and records consent")
    void acceptingTheCurrentVersionRegistersAndRecordsConsent() throws Exception {
        Instant beforeRequest = Instant.now();
        String currentVersion = currentVersionId();

        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true,"termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersion))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"));

        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
        assertThat(userId).as("the account was created").isNotNull();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM terms_acceptances WHERE user_id = ?", Integer.class, userId))
                .as("exactly one consent row, in the same transaction as the account")
                .isOne();

        assertThat(jdbc.queryForObject(
                "SELECT terms_version FROM terms_acceptances WHERE user_id = ?", String.class, userId))
                .as("consent is recorded against the exact version that was accepted")
                .isEqualTo(currentVersion);

        Timestamp acceptedAt = jdbc.queryForObject(
                "SELECT accepted_at FROM terms_acceptances WHERE user_id = ?", Timestamp.class, userId);
        assertThat(acceptedAt).isNotNull();
        assertThat(acceptedAt.toInstant())
                .as("server-generated from the injected Clock, not taken from the request")
                .isBetween(beforeRequest.minus(Duration.ofMinutes(1)),
                        Instant.now().plus(Duration.ofMinutes(1)));
    }

    @Test
    @DisplayName("consent is stored as timestamptz, unlike every other timestamp in this schema")
    void consentTimestampCarriesItsZone() {
        // The deliberate departure from the surrounding LocalDateTime columns. A wall-clock reading
        // is not a point in time without a zone, and this is the one column that may have to be read
        // as evidence long after whoever configured the JVM has gone.
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'terms_acceptances'
                  AND column_name = 'accepted_at'
                """, String.class))
                .isEqualTo("timestamp with time zone");
    }

    // ── Everything that is refused ────────────────────────────────────────────

    @Test
    @DisplayName("silence is not consent: an omitted termsAccepted is a 400 that creates nothing")
    void omittedAcceptanceIsRefused() throws Exception {
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersionId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors", hasItem(containsString("termsAccepted"))))
                // A field-level refusal, not a named business condition — the client fixes the form.
                .andExpect(jsonPath("$.code").doesNotExist());

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("an explicit false is refused, and is never quietly upgraded")
    void declinedAcceptanceIsRefused() throws Exception {
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":false,"termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersionId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("termsAccepted"))))
                .andExpect(jsonPath("$.code").doesNotExist());

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("the string \"true\" is not consent — a JSON boolean is the only accepted shape")
    void stringLiteralTrueIsRefused() throws Exception {
        // Jackson would ordinarily coerce this to true without complaint, which is exactly the
        // problem: an agreement has to be something the sender expressed, not something the parser
        // was generous enough to infer from a client bug.
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":"true","termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersionId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors[0]")
                        .value("The request body could not be read. "
                                + "Please check the format of the submitted values."));

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("the number 1 is not consent either")
    void numericTrueIsRefused() throws Exception {
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":1,"termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersionId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]")
                        .value("The request body could not be read. "
                                + "Please check the format of the submitted values."));

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("accepting without saying what was accepted is refused")
    void missingVersionIsRefused() throws Exception {
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true}
                """.formatted(EMAIL, PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("termsVersion"))))
                .andExpect(jsonPath("$.code").doesNotExist());

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("a blank version is refused for the same reason as a missing one")
    void blankVersionIsRefused() throws Exception {
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true,"termsVersion":"   "}
                """.formatted(EMAIL, PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString("termsVersion"))));

        assertNothingWasCreated();
    }

    @Test
    @DisplayName("a version this build never published is a 409 the client can act on")
    void unknownVersionIsRefusedAsOutdated() throws Exception {
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true,"termsVersion":"0.9-invented"}
                """.formatted(EMAIL, PASSWORD))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                // Named, so the client can refetch and re-ask rather than matching on prose.
                .andExpect(jsonPath("$.code").value("TERMS_VERSION_OUTDATED"));

        assertNothingWasCreated();
    }

    // ── The account that fails after consent was decided ──────────────────────

    @Test
    @DisplayName("a registration that creates no account leaves no orphan consent row")
    void failedAccountCreationLeavesNoConsentRow() throws Exception {
        // The address is already taken, so this creates nothing -- after the terms have been
        // resolved, and before anything is written. If consent were recorded at the moment it was
        // validated rather than alongside the account, this is where the orphan would appear. It is
        // answered like a new registration (SEC-F05), so only the tables can tell what happened.
        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true,"termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersionId()))
                .andExpect(status().isCreated());

        Map<String, Integer> afterFirst = rowCounts();

        register("""
                {"fullName":"Consent Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true,"termsVersion":"%s"}
                """.formatted(EMAIL, PASSWORD, currentVersionId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"));

        assertThat(rowCounts())
                .as("the second registration of a taken address wrote nothing at all")
                .isEqualTo(afterFirst);
    }

    // ── The people who were already here ──────────────────────────────────────

    @Test
    @DisplayName("an account that predates the table has no consent row and can still sign in")
    void existingAccountsAreUntouchedAndCanStillAuthenticate() throws Exception {
        // Inserted straight into the table, exactly as an account created before this feature
        // existed sits there today: verified, usable, and with nothing in terms_acceptances.
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified,
                                   requires_password_reset, role, created_at)
                VALUES (?, ?, ?, true, false, 'STUDENT', now()) RETURNING id
                """, Long.class, "Legacy Account", EMAIL, passwordEncoder.encode(PASSWORD));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM terms_acceptances WHERE user_id = ?", Integer.class, userId))
                .as("no row was fabricated for an account that was never asked — absence means "
                        + "unknown, not declined")
                .isZero();

        // The half that would be catastrophic to get wrong: requiring consent at registration must
        // not lock out everyone who registered before it was required.
        mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.email").value(EMAIL));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String currentVersionId() {
        return registry.current().orElseThrow().id();
    }

    private ResultActions register(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * The whole of what a refused registration must leave behind, which is nothing.
     *
     * <p>Row counts and the mail provider together: a check that ran after the OTP was generated
     * would still leave the count of {@code otps} unchanged if the transaction rolled back, but the
     * email would already have gone out to somebody who never finished signing up. Only asserting
     * both closes that.
     */
    private void assertNothingWasCreated() {
        assertThat(rowCounts())
                .as("a refused registration must create no account, no profile, no consent row "
                        + "and no OTP")
                .isEqualTo(before);

        verifyNoInteractions(emailService);
    }

    private Map<String, Integer> rowCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        WRITTEN_BY_REGISTRATION.forEach(table ->
                counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)));
        return counts;
    }
}
