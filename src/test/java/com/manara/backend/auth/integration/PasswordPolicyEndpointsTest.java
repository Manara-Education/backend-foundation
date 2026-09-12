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
import org.junit.jupiter.params.provider.ValueSource;
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
 * <p>The four requirements the forms show — 15 characters, an upper-case English letter, a digit and
 * a symbol — are sent straight to the API here, the way a caller that skips the form's own checks
 * would send them. The client's checklist is a convenience; this is the enforcement.
 *
 * <p>Driven through HTTP against the running application rather than against the validator: the
 * claim is about what the endpoints do, including that a refusal writes nothing — no account, no
 * new hash, no spent code, no revoked session.
 */
class PasswordPolicyEndpointsTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@passwordpolicy.example";
    private static final String SESSION_COOKIE = "MANARA_SESSION";
    private static final String ORIGINAL_PASSWORD = "sunlit harbour lantern 42";

    /** Arabic letters beside the three required characters: 33 code points, 57 UTF-8 bytes. */
    private static final String ARABIC_PASSPHRASE = "نخيل البحر يغني للقمر كل مساء Q7!";
    private static final String ASCII_PASSPHRASE_60 = "Lanterns drift past the quiet harbour wall every evening, 7!";

    /** 14 code points but 16 UTF-16 units, so counting {@code String.length()} would accept it. */
    private static final String FOURTEEN_CODE_POINTS = "river stone 😀😀";
    /** Meets all four requirements, and is one byte more than bcrypt can use. */
    private static final String ASCII_73_BYTES =
            "Lanterns drift past the quiet harbour wall every single evening at 7 pm!!";
    /** 35 Arabic letters, two bytes each, and the three required characters. */
    private static final String ARABIC_73_BYTES = "ب".repeat(35) + "A1!";

    private static final String TOO_SHORT = "at least 15 characters";
    private static final String MISSING_UPPERCASE = "uppercase English letter";
    private static final String MISSING_NUMBER = "at least one number";
    private static final String MISSING_SYMBOL = "special symbol";
    private static final String TOO_LONG = "too long";
    private static final String COMMON = "too common";
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
                new Refused("14 code points that are 16 UTF-16 units", FOURTEEN_CODE_POINTS, TOO_SHORT),
                new Refused("no upper-case letter or symbol", "password123456789", MISSING_UPPERCASE),
                new Refused("no symbol", "PASSWORD123456789", MISSING_SYMBOL),
                new Refused("no symbol, though mixed case", "PasswordPassword1", MISSING_SYMBOL),
                new Refused("no upper-case letter or digit", "abcdefghijklmno!", MISSING_UPPERCASE),
                new Refused("no digit", "Harbour lights at dusk!", MISSING_NUMBER),
                new Refused("a common entry that meets all four requirements", "11111_Fantastique", COMMON),
                new Refused("73 ASCII bytes", ASCII_73_BYTES, TOO_LONG),
                new Refused("35 Arabic letters and three more characters, 73 bytes", ARABIC_73_BYTES, TOO_LONG));
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
        assertThat(ARABIC_73_BYTES.getBytes(StandardCharsets.UTF_8)).hasSize(73);
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
        // Each of these meets the four requirements, so what is refused is whose details they are.
        String ownAddress = email.toUpperCase() + "1";

        expectRefusal(register(email, ownAddress), PERSONAL);
        expectRefusal(register(email, "Long-Address-Owner2026!"), PERSONAL);
        expectRefusal(register(email, "Manara manara 2026!"), PERSONAL);

        seedVerifiedAccount(email, ORIGINAL_PASSWORD);
        requestResetCode(email);
        expectRefusal(reset(email, outstandingCode(email), ownAddress), PERSONAL);

        Device device = signIn(email, ORIGINAL_PASSWORD);
        expectRefusal(changePassword(device, ORIGINAL_PASSWORD, ownAddress), PERSONAL);
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

    // ── What the refusal says ─────────────────────────────────────────────────

    @Test
    @DisplayName("a missing requirement is refused in the checklist's Arabic wording when the client asks for Arabic")
    void missingRequirementIsExplainedInArabic() throws Exception {
        assertThat(refusalBody("ar", "missing-symbol-ar" + DOMAIN, "PasswordPassword1"))
                .contains("يجب أن تحتوي كلمة المرور على رمز خاص واحد على الأقل.");
    }

    @ParameterizedTest(name = "a password too long to store is refused in \"{0}\" without naming bytes or hashing")
    @ValueSource(strings = {"en", "ar"})
    void tooLongRefusalNamesNoInternals(String language) throws Exception {
        assertThat(refusalBody(language, "too-long-" + language + DOMAIN, ARABIC_73_BYTES))
                .contains("ar".equals(language) ? "كلمة المرور طويلة جدًا" : "Password is too long")
                .doesNotContainIgnoringCase("byte", "بايت", "UTF", "bcrypt", "hash", "72");
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

    /** The body of a refused registration, in the language asked for. */
    private String refusalBody(String language, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, language)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody(email, password)))
                .andExpect(status().isBadRequest())
                .andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
