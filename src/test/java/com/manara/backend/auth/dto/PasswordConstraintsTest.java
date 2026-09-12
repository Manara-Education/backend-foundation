package com.manara.backend.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which request carries which password rule, and where a refusal is reported.
 *
 * <p>A plain Bean Validation factory, deliberately: it instantiates validators itself, so this also
 * proves the password constraints do not depend on Spring to work.
 */
class PasswordConstraintsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("registration reports each rule against the password field")
    void registrationAppliesThePolicy() {
        assertThat(violations(register("sara@example.com", "123456")))
                .containsExactly("password {validation.password.size}");
        assertThat(violations(register("sara@example.com", "passwordpassword")))
                .containsExactly("password {validation.password.common}");
        assertThat(violations(register("long.address.owner@example.com", "long.address.owner@example.com")))
                .containsExactly("password {validation.password.personal}");
        assertThat(violations(register("sara@example.com", "sunlit harbour lantern 42"))).isEmpty();
    }

    @Test
    @DisplayName("reset reports against newPassword, including the own-address rule")
    void resetAppliesThePolicy() {
        var ownAddress = ResetPasswordRequest.builder()
                .email("long.address.owner@example.com").code("123456")
                .newPassword("long.address.owner@example.com").build();
        var tooLong = ResetPasswordRequest.builder()
                .email("sara@example.com").code("123456").newPassword("ب".repeat(37)).build();

        assertThat(violations(ownAddress)).containsExactly("newPassword {validation.password.personal}");
        assertThat(violations(tooLong)).containsExactly("newPassword {validation.password.tooLong}");
    }

    @Test
    @DisplayName("change-password checks the new password only, never the current one")
    void changeAppliesThePolicyToTheNewPasswordOnly() {
        var request = ChangePasswordRequest.builder().currentPassword("123456").newPassword("123456").build();

        assertThat(violations(request)).containsExactly("newPassword {validation.password.size}");
    }

    @Test
    @DisplayName("sign-in applies no password policy, so accounts with old short passwords can still sign in")
    void loginIsUntouched() {
        assertThat(validator.validate(LoginRequest.builder().email("sara@example.com").password("123456").build()))
                .isEmpty();
    }

    @Test
    @DisplayName("no message and no toString ever carries the password")
    void refusalsNeverQuoteThePassword() {
        String secret = "long.address.owner@example.com";
        var request = register(secret, secret);

        assertThat(validator.validate(request))
                .isNotEmpty()
                .allSatisfy(v -> assertThat(v.getMessage()).doesNotContain(secret));
        assertThat(ChangePasswordRequest.builder().currentPassword("old secret value").newPassword("new secret value")
                .build().toString()).doesNotContain("secret value");
        assertThat(register("sara@example.com", "sunlit harbour lantern 42").toString()).doesNotContain("lantern");
    }

    private static RegisterRequest register(String email, String password) {
        return RegisterRequest.builder()
                .fullName("Test Person").email(email).password(password)
                .termsAccepted(true).termsVersion("1.0").build();
    }

    private <T> Set<String> violations(T request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessageTemplate())
                .collect(java.util.stream.Collectors.toSet());
    }
}
