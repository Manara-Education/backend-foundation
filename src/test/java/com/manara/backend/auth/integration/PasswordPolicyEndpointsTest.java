package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.service.TermsVersionRegistry;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-F02. What a new password has to be, asked of every endpoint that sets one.
 *
 * <p>The pentest registered an account with {@code 123456}. Registration, change-password and
 * reset-password carried only {@code @Size(min = 6)}, nothing compared a password with the lists
 * attackers try first, and a password longer than the 72 bytes bcrypt can use made the encoder
 * throw — a 500. Every refusal here is asserted as a 400 carrying the policy's own reason, at all
 * three endpoints, because a rule two of them enforce is a rule with a side door.
 *
 * <p>Driven through HTTP against the running application rather than against the validator: the
 * claim is about what the endpoints do, including that a refusal writes nothing — no account, no
 * new hash, no spent code, no revoked session.
 */
class PasswordPolicyEndpointsTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@passwordpolicy.example";
    private static final String SESSION_COOKIE = "MANARA_SESSION";
    private static final String ORIGINAL_PASSWORD = "sunlit harbour lantern 42";

    /** 29 code points, 53 UTF-8 bytes: within the limit although well past 15 letters. */
    private static final String ARABIC_PASSPHRASE = "نخيل البحر يغني للقمر كل مساء";
    private static final String ASCII_PASSPHRASE_60 = "lanterns drift past the quiet harbour wall every evening now";

    /** 14 code points but 16 UTF-16 units, so counting {@code String.length()} would accept it. */
    private static final String FOURTEEN_CODE_POINTS = "river stone 😀😀";
    private static final String ASCII_73_BYTES =
            "lanterns drift past the quiet harbour wall every single evening at dusk!!";
    /** 37 Arabic letters, two bytes each. */
    private static final String ARABIC_74_BYTES = "الشمسوالقمروالنجوموالبحروالجبالوالسهل";

    private static final String TOO_SHORT = "at least 15 characters";
    private static final String TOO_LONG = "72 bytes";
    private static final String COMMON = "common or leaked";
    private static final String PERSONAL = "your email address";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private jakarta.servlet.Filter sessionRepositoryFilter;

    /** A password the policy must refuse, and the fragment of the English reason that says why. */
    record Refused(String description, String password, String reason) {
        @Override
        public String toString() {
            return description;
        }
    }

    static List<Refused> refusedPasswords() {
        return List.of(
                new Refused("the pentest's 123456", "123456", TOO_SHORT),
                new Refused("a 16-character entry on the common-password list", "passwordpassword", COMMON),
                new Refused("14 code points that are 16 UTF-16 units", FOURTEEN_CODE_POINTS, TOO_SHORT),
                new Refused("73 ASCII bytes", ASCII_73_BYTES, TOO_LONG),
                new Refused("37 Arabic letters, 74 bytes", ARABIC_74_BYTES, TOO_LONG));
    }

    @BeforeEach
    void buildMockMvc() {
        // The session filter is added explicitly for the change-password cases, which sign in and
        // carry the MANARA_SESSION cookie exactly as SessionRevocationTest does.
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter)
                .apply(springSecurity())
                .build();
        removeTestAccounts();
    }

    @AfterEach
    void removeTestAccounts() {
        for (String table : List.of("terms_acceptances", "otps", "students", "instructors")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                    "%" + DOMAIN);
        }
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    @DisplayName("the fixtures measure what their names claim")
    void fixturesAreWhatTheySayTheyAre() {
        // Without this a "73 bytes" case that was really 72 would pass for the wrong reason.
        assertThat(FOURTEEN_CODE_POINTS.codePointCount(0, FOURTEEN_CODE_POINTS.length())).isEqualTo(14);
        assertThat(FOURTEEN_CODE_POINTS.length()).isEqualTo(16);
        assertThat(ASCII_73_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(73);
        assertThat(ARABIC_74_BYTES.codePointCount(0, ARABIC_74_BYTES.length())).isEqualTo(37);
        assertThat(ARABIC_74_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(74);
        assertThat(ARABIC_PASSPHRASE.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(72);
        assertThat(ASCII_PASSPHRASE_60).hasSize(60);
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @ParameterizedTest(name = "registration refuses {0}")
    @MethodSource("refusedPasswords")
    void registrationRefuses(Refused refused) throws Exception {
        String email = "refused-registration" + DOMAIN;

        expectRefusal(register(email, refused.password()), refused.reason());

        assertThat(userRepository.findByEmail(email)).as("a refused registration creates no account").isEmpty();
    }

    @Test
    @DisplayName("registration accepts an Arabic passphrase and a 60-character one with spaces")
    void registrationAcceptsPassphrases() throws Exception {
        register("arabic-passphrase" + DOMAIN, ARABIC_PASSPHRASE).andExpect(status().isCreated());
        register("ascii-passphrase" + DOMAIN, ASCII_PASSPHRASE_60).andExpect(status().isCreated());

        assertThat(storedHashMatches("arabic-passphrase" + DOMAIN, ARABIC_PASSPHRASE))
                .as("the password is hashed exactly as sent, not normalised or trimmed")
                .isTrue();
    }

    // ── Reset with an emailed code ────────────────────────────────────────────

    @ParameterizedTest(name = "reset-password refuses {0}")
    @MethodSource("refusedPasswords")
    void resetRefuses(Refused refused) throws Exception {
        String email = "refused-reset" + DOMAIN;
        seedVerifiedAccount(email, ORIGINAL_PASSWORD);
        requestResetCode(email);
        String code = outstandingCode(email);

        expectRefusal(reset(email, code, refused.password()), refused.reason());

        assertThat(storedHashMatches(email, ORIGINAL_PASSWORD)).as("the old password still stands").isTrue();
        assertThat(outstandingCode(email)).as("a refused password does not spend the emailed code").isEqualTo(code);
    }

    @Test
    @DisplayName("reset-password accepts an Arabic passphrase and a 60-character one, and both sign in")
    void resetAcceptsPassphrases() throws Exception {
        String email = "accepted-reset" + DOMAIN;
        seedVerifiedAccount(email, ORIGINAL_PASSWORD);

        requestResetCode(email);
        reset(email, outstandingCode(email), ARABIC_PASSPHRASE).andExpect(status().isOk());
        signIn(email, ARABIC_PASSPHRASE);

        requestResetCode(email);
        reset(email, outstandingCode(email), ASCII_PASSPHRASE_60).andExpect(status().isOk());
        signIn(email, ASCII_PASSPHRASE_60);
    }

    // ── Change while signed in ────────────────────────────────────────────────

    @ParameterizedTest(name = "change-password refuses {0}")
    @MethodSource("refusedPasswords")
    void changeRefuses(Refused refused) throws Exception {
        String email = "refused-change" + DOMAIN;
        seedVerifiedAccount(email, ORIGINAL_PASSWORD);
        Device device = signIn(email, ORIGINAL_PASSWORD);

        expectRefusal(changePassword(device, ORIGINAL_PASSWORD, refused.password()), refused.reason());

        assertThat(storedHashMatches(email, ORIGINAL_PASSWORD)).as("the old password still stands").isTrue();
        assertThat(authVersion(email)).as("a refused change revokes no session").isZero();
    }

    @Test
    @DisplayName("change-password accepts an Arabic passphrase and a 60-character one")
    void changeAcceptsPassphrases() throws Exception {
        String email = "accepted-change" + DOMAIN;
        seedVerifiedAccount(email, ORIGINAL_PASSWORD);
        Device device = signIn(email, ORIGINAL_PASSWORD);

        changePassword(device, ORIGINAL_PASSWORD, ARABIC_PASSPHRASE).andExpect(status().isOk());
        changePassword(device, ARABIC_PASSPHRASE, ASCII_PASSPHRASE_60).andExpect(status().isOk());

        signIn(email, ASCII_PASSPHRASE_60);
    }

    // ── Context-specific passwords ────────────────────────────────────────────

    @Test
    @DisplayName("the account's own address, its local part and the service name are refused everywhere")
    void personalPasswordsAreRefused() throws Exception {
        String email = "long-address-owner" + DOMAIN;

        expectRefusal(register(email, email), PERSONAL);
        expectRefusal(register(email, "long-address-owner2026"), PERSONAL);
        expectRefusal(register(email, "Manara manara 2026!"), PERSONAL);

        seedVerifiedAccount(email, ORIGINAL_PASSWORD);
        requestResetCode(email);
        expectRefusal(reset(email, outstandingCode(email), email), PERSONAL);

        Device device = signIn(email, ORIGINAL_PASSWORD);
        expectRefusal(changePassword(device, ORIGINAL_PASSWORD, email.toUpperCase()), PERSONAL);
        assertThat(storedHashMatches(email, ORIGINAL_PASSWORD)).isTrue();
    }

    // ── Existing accounts ─────────────────────────────────────────────────────

    @Test
    @DisplayName("an account whose stored password predates the policy still signs in")
    void existingShortPasswordStillSignsIn() throws Exception {
        // Seeded as a hash, the way an account created under the old six-character rule sits in
        // the database. Sign-in must not apply the new policy: it would lock those people out.
        String email = "legacy-account" + DOMAIN;
        seedVerifiedAccount(email, "sunshine");

        signIn(email, "sunshine");
    }

    @Test
    @DisplayName("the byte limit is explained in Arabic when the client asks for Arabic")
    void byteLimitIsExplainedInArabic() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "ar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("arabic-locale" + DOMAIN, ARABIC_74_BYTES)))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("72")
                .contains("بايت");
    }

    // ------------------------------------------------------------------ helpers

    private static final class Device {
        private String cookie;

        private Device(String cookie) {
            this.cookie = cookie;
        }

        private Cookie asCookie() {
            return new Cookie(SESSION_COOKIE, cookie);
        }

        /** Follows the cookie when the response rotated it, as a successful change does. */
        private void follow(MvcResult result) {
            Cookie issued = result.getResponse().getCookie(SESSION_COOKIE);
            if (issued != null && issued.getValue() != null && !issued.getValue().isEmpty()) {
                cookie = issued.getValue();
            }
        }
    }

    private void expectRefusal(ResultActions actions, String reason) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasItem(containsString(reason))));
    }

    private ResultActions register(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationBody(email, password)));
    }

    private String registrationBody(String email, String password) {
        return """
                {"fullName":"Policy Test","email":%s,"password":%s,
                 "termsAccepted":true,"termsVersion":%s}"""
                .formatted(json(email), json(password), json(termsVersionRegistry.current().orElseThrow().id()));
    }

    private void seedVerifiedAccount(String email, String rawPassword) {
        userRepository.save(User.builder()
                .fullName("Policy Test")
                .email(email)
                .password(passwordEncoder.encode(rawPassword))
                .role(Role.STUDENT)
                .emailVerified(true)
                .build());
    }

    private Device signIn(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":%s,\"password\":%s}".formatted(json(email), json(password))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie issued = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(issued).as("a successful sign-in issues a session cookie").isNotNull();
        return new Device(issued.getValue());
    }

    private ResultActions changePassword(Device device, String current, String replacement) throws Exception {
        ResultActions actions = mockMvc.perform(post("/api/v1/auth/change-password").with(csrf())
                .cookie(device.asCookie())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":%s,\"newPassword\":%s}".formatted(json(current), json(replacement))));
        device.follow(actions.andReturn());
        return actions;
    }

    private void requestResetCode(String email) throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":%s}".formatted(json(email))))
                .andExpect(status().isOk());
    }

    private ResultActions reset(String email, String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/reset-password").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":%s,\"code\":%s,\"newPassword\":%s}"
                        .formatted(json(email), json(code), json(newPassword))));
    }

    private boolean storedHashMatches(String email, String rawPassword) {
        return passwordEncoder.matches(rawPassword, userRepository.findByEmail(email).orElseThrow().getPassword());
    }

    private Long authVersion(String email) {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE email = ?", Long.class, email);
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

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
