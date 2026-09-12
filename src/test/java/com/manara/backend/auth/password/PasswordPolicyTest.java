package com.manara.backend.auth.password;

import com.manara.backend.auth.password.PasswordPolicy.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules themselves, one at a time. What each endpoint does with them is
 * {@code PasswordPolicyEndpointsTest}'s subject.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = PasswordPolicy.standard();

    @Test
    @DisplayName("length is counted in code points, so an emoji is one character, not two")
    void countsCodePoints() {
        assertThat(policy.check("river stone 😀😀")).contains(Violation.TOO_SHORT);
        assertThat(policy.check("river stone 😀😀😀")).isEmpty();
        assertThat(policy.check("١٢ نخيل وقمر ٤٥")).as("15 Arabic code points").isEmpty();
    }

    @Test
    @DisplayName("the ceiling is 72 UTF-8 bytes: 72 Latin or 36 Arabic letters fit, one more does not")
    void ceilingIsBcryptsByteLimit() {
        String ascii72 = "lanterns drift past the quiet harbour wall every single evening at dusk!";
        String arabic36 = "الشمسوالقمروالنجوموالبحروالجبالوالسه";
        assertThat(ascii72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(arabic36.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        assertThat(policy.check(ascii72)).isEmpty();
        assertThat(policy.check(ascii72 + "!")).contains(Violation.TOO_LONG);
        assertThat(policy.check(arabic36)).isEmpty();
        assertThat(policy.check(arabic36 + "ل")).contains(Violation.TOO_LONG);
    }

    @Test
    @DisplayName("the bundled list is compared case-insensitively and after NFKC")
    void blocklistIgnoresCaseAndCompatibilityForms() {
        assertThat(policy.check("passwordpassword")).contains(Violation.COMMON);
        assertThat(policy.check("PassWordPassWord")).contains(Violation.COMMON);
        assertThat(policy.check("ｐａｓｓｗｏｒｄｐａｓｓｗｏｒｄ")).as("full-width Latin").contains(Violation.COMMON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaaaaaaaa", "               ", "abababababababab", "1234123412341234", "ههههههههههههههههه"})
    @DisplayName("one character, or a unit of up to four, repeated to length is refused")
    void shortRepetitionIsRefused(String password) {
        assertThat(policy.check(password)).contains(Violation.REPETITIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"manaramanaramanara", "Manara@2026!!!!!!", "منارة منارة 2026", "MANARA 1234567890"})
    @DisplayName("the service's name padded with digits and punctuation is refused")
    void serviceNameIsRefused(String password) {
        assertThat(policy.check(password)).contains(Violation.PERSONAL);
    }

    @Test
    @DisplayName("spaces, Arabic and no composition at all are fine; a passphrase mentioning the service is too")
    void passphrasesAreAccepted() {
        assertThat(policy.check("  ember lantern 7  ")).isEmpty();
        assertThat(policy.check("نخيل البحر يغني للقمر كل مساء")).isEmpty();
        assertThat(policy.check("the lighthouse at manara bay")).isEmpty();
        assertThat(policy.check(null)).as("@NotBlank reports a missing password").isEmpty();
    }

    @Test
    @DisplayName("the account's address, its local part and the holder's name are refused, with filler too")
    void personalPasswordsAreRefused() {
        String email = "sara.ahmed@example.com";
        String name = "Sara Ahmed";

        assertThat(policy.isAboutAccount("Sara.Ahmed@Example.com", email, name)).isTrue();
        assertThat(policy.isAboutAccount("SARA.AHMED2026!!", email, name)).isTrue();
        assertThat(policy.isAboutAccount("sara ahmed 1234567", email, name)).isTrue();
        assertThat(policy.isAboutAccount("saraahmed12345678", email, name)).isTrue();

        assertThat(policy.isAboutAccount("sara walks by the river", email, name)).isFalse();
        assertThat(policy.isAboutAccount("al al 1234567890123", "al@x.com", null))
                .as("a two-letter local part is only matched exactly").isFalse();
        assertThat(policy.isAboutAccount("sara.ahmed@example.com", null, null)).isFalse();
    }

    @Test
    @DisplayName("every rule has an English and an Arabic message, and none is the old six-character one")
    void everyRuleHasLocalisedMessages() throws IOException {
        for (String bundle : new String[]{"/messages.properties", "/messages_ar.properties"}) {
            Properties messages = new Properties();
            try (var in = new InputStreamReader(getClass().getResourceAsStream(bundle), StandardCharsets.UTF_8)) {
                messages.load(in);
            }
            for (Violation violation : Violation.values()) {
                assertThat(messages.getProperty(violation.messageKey()))
                        .as(bundle + " " + violation.messageKey())
                        .isNotBlank()
                        // Bean Validation would try to interpolate these.
                        .doesNotContain("{", "}", "$");
            }
            assertThat(messages.getProperty("validation.password.size")).contains("15").doesNotContain("6 ");
            assertThat(messages.getProperty("validation.password.tooLong")).contains("72");
        }
    }
}
