package com.manara.backend.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every request DTO that carries an email canonicalises it while binding.
 *
 * <p>This is the cheap test for an expensive mistake. {@code @JsonDeserialize} is silent when it is
 * not applied: the field binds normally, the request succeeds, and the address is simply stored raw
 * — no error anywhere. Getting it wrong is easy, because Spring Boot 4 binds request bodies with
 * <strong>Jackson 3</strong> ({@code tools.jackson}) while Jackson 2 remains on the classpath
 * transitively, so the Jackson 2 annotation of the same name compiles perfectly and does nothing.
 * That is exactly what happened while this was being written, and it was only caught by an
 * end-to-end test that needed Docker and a minute to run.
 *
 * <p>So the mapper here is deliberately the Jackson 3 one — the same implementation Spring MVC
 * uses. Binding with a Jackson 2 {@code ObjectMapper} would prove nothing about the running
 * application.
 */
class AuthRequestEmailBindingTest {

    private static final String CANONICAL = "ali@x.com";

    private final JsonMapper json = JsonMapper.builder().build();
    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest(name = "RegisterRequest binds \"{0}\" as ali@x.com")
    @ValueSource(strings = {"ali@x.com", "Ali@x.com", "ALI@X.COM", "  Ali@x.com  ", "\tALI@X.COM "})
    @DisplayName("registration canonicalises the address as it binds")
    void registerRequestCanonicalisesEmail(String raw) {
        RegisterRequest request = json.readValue(
                """
                {"fullName":"Ali","email":%s,"password":"password123"}
                """.formatted(quote(raw)), RegisterRequest.class);

        assertThat(request.getEmail()).isEqualTo(CANONICAL);
    }

    @ParameterizedTest(name = "LoginRequest binds \"{0}\" as ali@x.com")
    @ValueSource(strings = {"Ali@x.com", "ALI@X.COM", "  ali@x.com  "})
    @DisplayName("sign-in canonicalises the address as it binds")
    void loginRequestCanonicalisesEmail(String raw) {
        LoginRequest request = json.readValue(
                """
                {"email":%s,"password":"password123"}
                """.formatted(quote(raw)), LoginRequest.class);

        assertThat(request.getEmail()).isEqualTo(CANONICAL);
    }

    @Test
    @DisplayName("every other email-carrying request canonicalises too")
    void allRemainingRequestsCanonicaliseEmail() {
        String padded = quote("  ALI@X.com  ");

        assertThat(json.readValue("{\"email\":%s,\"code\":\"123456\"}".formatted(padded),
                OtpVerifyRequest.class).getEmail()).isEqualTo(CANONICAL);
        assertThat(json.readValue("{\"email\":%s}".formatted(padded),
                ResendOtpRequest.class).getEmail()).isEqualTo(CANONICAL);
        assertThat(json.readValue("{\"email\":%s}".formatted(padded),
                ForgotPasswordRequest.class).getEmail()).isEqualTo(CANONICAL);
        assertThat(json.readValue(
                "{\"email\":%s,\"code\":\"123456\",\"newPassword\":\"password123\"}".formatted(padded),
                ResetPasswordRequest.class).getEmail()).isEqualTo(CANONICAL);
    }

    @Test
    @DisplayName("a padded address survives @Email validation, because it is trimmed first")
    void paddedAddressPassesValidation() {
        // The ordering that makes canonicalising at bind time worth doing. Surrounding spaces are
        // not legal in an address, so "  Ali@x.com  " left untrimmed would be reported to the user
        // as an invalid email — for an address that is perfectly valid.
        RegisterRequest request = json.readValue(
                """
                {"fullName":"Ali","email":"  Ali@x.com  ","password":"password123"}
                """, RegisterRequest.class);

        assertThat(validator.validate(request))
                .as("validation rejected a canonicalised address")
                .isEmpty();
    }

    @Test
    @DisplayName("a genuinely invalid address is still rejected")
    void invalidAddressIsStillRejected() {
        RegisterRequest request = json.readValue(
                """
                {"fullName":"Ali","email":"  not-an-address  ","password":"password123"}
                """, RegisterRequest.class);

        assertThat(validator.validate(request))
                .as("trimming must not turn a broken address into an acceptable one")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a blank address stays blank so @NotBlank reports it")
    void blankAddressStaysBlank() {
        RegisterRequest request = json.readValue(
                """
                {"fullName":"Ali","email":"   ","password":"password123"}
                """, RegisterRequest.class);

        assertThat(request.getEmail()).isEmpty();
        assertThat(validator.validate(request)).isNotEmpty();
    }

    private static String quote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\t", "\\t").replace("\n", "\\n") + "\"";
    }
}
