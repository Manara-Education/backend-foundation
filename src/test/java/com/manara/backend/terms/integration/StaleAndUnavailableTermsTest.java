package com.manara.backend.terms.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.model.TermsVersion;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two conditions the shipped registry cannot currently produce, driven end to end anyway.
 *
 * <p>Only one version has ever been published, so through the real registry there is no such thing
 * as a superseded version and no way for "which version is current?" to go unanswered. Both cases
 * are certain to arrive — the first on the day the terms are next revised, the second if a future
 * registry resolves its answer from somewhere that can fail — and both are precisely the moments
 * where getting it wrong is expensive: a superseded version silently accepted records consent to
 * text nobody agreed to, and an unresolvable one silently ignored creates accounts with no consent
 * at all.
 *
 * <p>So the registry is replaced with a stub here. That is the only thing faked: the request goes
 * through the real filter chain, the real controller, the real service, the real transaction and the
 * real database, and what is asserted is the response the client would get and the rows that would
 * be left behind.
 */
class StaleAndUnavailableTermsTest extends AbstractPostgresBackedTest {

    private static final String EMAIL = "stale@termsstale.example";
    private static final String PASSWORD = "password123";

    /** The version the client believes in — real, published, and no longer the one in force. */
    private static final TermsVersion SUPERSEDED =
            new TermsVersion("1.0", LocalDate.of(2026, 9, 7));

    /** What the server has moved on to while that client's form sat open. */
    private static final TermsVersion IN_FORCE =
            new TermsVersion("2.0", LocalDate.of(2027, 1, 1));

    private static final List<String> WRITTEN_BY_REGISTRATION =
            List.of("users", "students", "instructors", "otps", "terms_acceptances");

    private MockMvc mockMvc;
    private Map<String, Integer> before;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private TermsVersionRegistry registry;

    @BeforeEach
    void buildMockMvcAndTakeBaseline() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        before = rowCounts();
    }

    // ── A version that was real, and no longer is ─────────────────────────────

    @Test
    @DisplayName("a superseded version is refused with the same code as an unknown one")
    void supersededVersionIsRefusedAsOutdated() throws Exception {
        given(registry.current()).willReturn(Optional.of(IN_FORCE));
        given(registry.isKnown(SUPERSEDED.id())).willReturn(true);

        // The form was opened before the terms were revised and submitted after. Accepting this
        // would record agreement to text that is no longer the agreement.
        register(SUPERSEDED.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("error"))
                // Deliberately the same code an unknown version gets: the remedy is identical —
                // re-read the current terms and accept them — so a client has nothing to do
                // differently, and only the server log tells the two apart.
                .andExpect(jsonPath("$.code").value("TERMS_VERSION_OUTDATED"));

        assertNothingWasCreated();
    }

    // ── A server that cannot say what is current ──────────────────────────────

    @Test
    @DisplayName("an unresolvable current version is a 503, never a silently skipped check")
    void unresolvableVersionRefusesRegistration() throws Exception {
        given(registry.current()).willReturn(Optional.empty());

        register(SUPERSEDED.id())
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("TERMS_UNAVAILABLE"));

        // The whole point of the 503. "I do not know which version is current" must never collapse
        // into "no version was required" — that would create an account with no record of what its
        // owner agreed to, which is the one outcome this feature exists to prevent.
        assertNothingWasCreated();
    }

    @Test
    @DisplayName("the publication endpoint says 503 too, rather than guessing a version")
    void unresolvableVersionIsNotGuessedAtByTheEndpoint() throws Exception {
        given(registry.current()).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/terms/current"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("TERMS_UNAVAILABLE"))
                // Never an older version dressed up as the current one: a client that rendered
                // superseded text under a current heading would collect meaningless consent.
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ResultActions register(String termsVersion) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"Stale Test","email":"%s","password":"%s","role":"STUDENT",
                         "termsAccepted":true,"termsVersion":"%s"}
                        """.formatted(EMAIL, PASSWORD, termsVersion)));
    }

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
