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
    @DisplayName("the four requirements: 15 characters, an upper-case English letter, a digit and a symbol")
    void theFourRequirements() {
        assertThat(policy.check("password123456789")).contains(Violation.MISSING_UPPERCASE);
        assertThat(policy.check("PASSWORD123456789")).contains(Violation.MISSING_SYMBOL);
        assertThat(policy.check("PasswordPassword1")).contains(Violation.MISSING_SYMBOL);
        assertThat(policy.check("abcdefghijklmno!")).contains(Violation.MISSING_UPPERCASE);
        assertThat(policy.check("Harbour lights at dusk!")).contains(Violation.MISSING_NUMBER);
        assertThat(policy.check("Short 1!")).contains(Violation.TOO_SHORT);

        assertThat(policy.check("ExamplePassword1!")).isEmpty();
        assertThat(policy.check("SecureAccount2026#")).isEmpty();
    }

    @Test
    @DisplayName("one of each is enough: no lower case, no second digit or symbol, no particular symbol")
    void nothingBeyondTheFourIsRequired() {
        assertThat(policy.check("NO LOWER CASE 1!")).isEmpty();
        assertThat(policy.check("Harbour lights 7_")).as("an underscore").isEmpty();
        assertThat(policy.check("Harbour lights 7؟")).as("an Arabic question mark").isEmpty();
        assertThat(policy.check("Harbour lights 7€")).as("a currency sign").isEmpty();
        assertThat(policy.check("Harbour lights 7😀")).as("an emoji").isEmpty();
    }

    @Test
    @DisplayName("upper case means A to Z and a digit 0 to 9; a space is no symbol; Arabic letters stay allowed")
    void characterRequirementsAreExact() {
        assertThat(policy.check("Ｈarbour lights 7 at dusk!")).as("a full-width capital")
                .contains(Violation.MISSING_UPPERCASE);
        assertThat(policy.check("Harbour lights ٣ at dusk!")).as("an Arabic-Indic digit")
                .contains(Violation.MISSING_NUMBER);
        assertThat(policy.check("Harbour lights at dusk 7")).as("a space")
                .contains(Violation.MISSING_SYMBOL);
        assertThat(policy.check("نخيل البحر يغني Q7!")).isEmpty();
    }

    @Test
    @DisplayName("length is counted in code points, so an emoji is one character, not two")
    void countsCodePoints() {
        assertThat("River stone 1😀".length()).as("UTF-16 units").isEqualTo(15);
        assertThat(policy.check("River stone 1😀")).contains(Violation.TOO_SHORT);
        assertThat(policy.check("River stone 1😀😀")).isEmpty();
    }

    @Test
    @DisplayName("the ceiling is 72 UTF-8 bytes: 72 Latin characters fit, and 34 Arabic letters with four more")
    void ceilingIsBcryptsByteLimit() {
        String ascii72 = "Lanterns drift past the quiet harbour wall every single evening at 7 pm!";
        String arabic72 = "ب".repeat(34) + "A1!?";
        assertThat(ascii72.getBytes(StandardCharsets.UTF_8)).hasSize(72);
        assertThat(arabic72.getBytes(StandardCharsets.UTF_8)).hasSize(72);

        assertThat(policy.check(ascii72)).isEmpty();
        assertThat(policy.check(ascii72 + "!")).contains(Violation.TOO_LONG);
        assertThat(policy.check(arabic72)).isEmpty();
        assertThat(policy.check("ب" + arabic72)).contains(Violation.TOO_LONG);
    }

    @Test
    @DisplayName("the bundled list is compared case-insensitively and after NFKC")
    void blocklistIgnoresCaseAndCompatibilityForms() {
        assertThat(policy.check("11111_Fantastique")).contains(Violation.COMMON);
        assertThat(policy.check("11111_FANTASTIQUE")).contains(Violation.COMMON);
        assertThat(policy.check("11111_FＡＮＴＡＳＴＩＱＵＥ")).as("full-width Latin").contains(Violation.COMMON);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A1!A1!A1!A1!A1!", "Ab1!Ab1!Ab1!Ab1!", "Ab1!Ab1!Ab1!Ab1!Ab"})
    @DisplayName("a unit of up to four repeated to length is refused, though it has all four kinds of character")
    void shortRepetitionIsRefused(String password) {
        assertThat(policy.check(password)).contains(Violation.REPETITIVE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Manaramanaramanara1!", "Manara@2026!!!!!!", "MANARA 1234567890!"})
    @DisplayName("the service's name padded with digits and punctuation is refused")
    void serviceNameIsRefused(String password) {
        assertThat(policy.check(password)).contains(Violation.PERSONAL);
    }

    @Test
    @DisplayName("spaces and Arabic are fine beside the four requirements; so is a passphrase mentioning the service")
    void passphrasesAreAccepted() {
        assertThat(policy.check("  Ember lantern 7!  ")).isEmpty();
        assertThat(policy.check("نخيل البحر يغني للقمر كل مساء Q7!")).isEmpty();
        assertThat(policy.check("The lighthouse at Manara bay 7!")).isEmpty();
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
    @DisplayName("every rule has an English and an Arabic message, and none says how the password is stored")
    void everyRuleHasLocalisedMessages() throws IOException {
        for (String bundle : new String[]{"/messages.properties", "/messages_ar.properties"}) {
            Properties messages = load(bundle);
            for (Violation violation : Violation.values()) {
                assertThat(messages.getProperty(violation.messageKey()))
                        .as(bundle + " " + violation.messageKey())
                        .isNotBlank()
                        // Bean Validation would try to interpolate these.
                        .doesNotContain("{", "}", "$")
                        .doesNotContainIgnoringCase("byte", "بايت", "UTF", "bcrypt", "hash", "encod", "ترميز", "72");
            }
            assertThat(messages.getProperty("validation.password.size")).contains("15").doesNotContain("6 ");
        }
    }

    @Test
    @DisplayName("the Arabic refusals are the client checklist's wording, word for word")
    void arabicMessagesMatchTheClientChecklist() throws IOException {
        // Written out, as the client's password-policy.test.ts writes them out: a reword on either
        // side fails a test on that side, and has to be carried to the other.
        Properties arabic = load("/messages_ar.properties");

        assertThat(arabic.getProperty("validation.password.size"))
                .isEqualTo("يجب أن تتكون كلمة المرور من 15 حرفًا على الأقل.");
        assertThat(arabic.getProperty("validation.password.uppercase"))
                .isEqualTo("يجب أن تحتوي كلمة المرور على حرف إنجليزي كبير واحد على الأقل.");
        assertThat(arabic.getProperty("validation.password.number"))
                .isEqualTo("يجب أن تحتوي كلمة المرور على رقم واحد على الأقل.");
        assertThat(arabic.getProperty("validation.password.symbol"))
                .isEqualTo("يجب أن تحتوي كلمة المرور على رمز خاص واحد على الأقل.");
        assertThat(arabic.getProperty("validation.password.tooLong"))
                .isEqualTo("كلمة المرور طويلة جدًا. يرجى اختيار كلمة مرور أقصر.");
    }

    private Properties load(String bundle) throws IOException {
        Properties messages = new Properties();
        try (var in = new InputStreamReader(getClass().getResourceAsStream(bundle), StandardCharsets.UTF_8)) {
            messages.load(in);
        }
        return messages;
    }
}
