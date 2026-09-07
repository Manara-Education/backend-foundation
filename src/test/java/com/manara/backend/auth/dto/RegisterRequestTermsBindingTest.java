package com.manara.backend.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consent binds from a JSON boolean and from nothing else, and a missing one is never a "no".
 *
 * <p>The cheap test for two expensive mistakes, neither of which announces itself.
 *
 * <p>The first is {@code @JsonDeserialize} silently not applying. Spring Boot 4 binds request bodies
 * with <strong>Jackson 3</strong> ({@code tools.jackson}) while Jackson 2 is still on the classpath
 * transitively, so a deserializer written against the Jackson 2 base class compiles, deploys, and
 * does nothing at all — leaving {@code "true"} coerced to {@code true} exactly as before. The mapper
 * here is therefore deliberately the Jackson 3 one, the same implementation Spring MVC uses;
 * asserting against a Jackson 2 mapper would prove nothing about the running application.
 *
 * <p>The second is the annotation pair on the field. {@code @AssertTrue} passes on {@code null} by
 * specification, so on its own it lets an omitted field straight through; a primitive {@code boolean}
 * would bind a missing field to {@code false} and make "did not answer" indistinguishable from
 * "declined". Both cases are checked below, because both would ship an application that creates
 * accounts for people who never agreed to anything.
 */
class RegisterRequestTermsBindingTest {

    private static final String VERSION = "1.0";

    private final JsonMapper json = JsonMapper.builder().build();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("an explicit true binds and the request is valid")
    void explicitTrueIsAccepted() {
        RegisterRequest request = read("true", "\"" + VERSION + "\"");

        assertThat(request.getTermsAccepted()).isTrue();
        assertThat(request.getTermsVersion()).isEqualTo(VERSION);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("an explicit false binds, and is rejected as a refusal rather than ignored")
    void explicitFalseIsRejected() {
        RegisterRequest request = read("false", "\"" + VERSION + "\"");

        assertThat(request.getTermsAccepted()).isFalse();
        assertThat(violatedFields(request)).contains("termsAccepted");
    }

    @Test
    @DisplayName("an omitted field is null, and is reported — @AssertTrue alone would let it pass")
    void omittedAcceptanceIsRejected() {
        RegisterRequest request = json.readValue("""
                {"fullName":"Ali","email":"ali@x.com","password":"password123",
                 "termsVersion":"%s"}
                """.formatted(VERSION), RegisterRequest.class);

        assertThat(request.getTermsAccepted())
                .as("boxed Boolean, so silence stays distinguishable from a deliberate no")
                .isNull();
        assertThat(violatedFields(request)).contains("termsAccepted");
    }

    @Test
    @DisplayName("an explicit JSON null is reported, not read as a refusal or a default")
    void explicitNullIsRejected() {
        RegisterRequest request = read("null", "\"" + VERSION + "\"");

        assertThat(request.getTermsAccepted()).isNull();
        assertThat(violatedFields(request)).contains("termsAccepted");
    }

    @ParameterizedTest(name = "termsAccepted: {0} is refused at parse time")
    @ValueSource(strings = {"\"true\"", "\"True\"", "\"yes\"", "\"1\"", "1", "0", "\"\"", "[]", "{}"})
    @DisplayName("anything that is not a JSON boolean fails to bind at all")
    void nonBooleanShapesAreRefused(String rawJson) {
        // Jackson would coerce most of these without complaint — "true", "1" and 1 to true; "", "0"
        // and 0 to false. That is fine for a preference flag and wrong for a record of agreement:
        // consent has to be something the sender expressed, not something the parser inferred from
        // a client bug. The throw becomes HttpMessageNotReadableException and the project's
        // existing 400 for a malformed body.
        assertThatThrownBy(() -> read(rawJson, "\"" + VERSION + "\""))
                .isInstanceOf(DatabindException.class);
    }

    @Test
    @DisplayName("a missing version is reported even when acceptance is a perfectly good true")
    void missingVersionIsRejected() {
        RegisterRequest request = json.readValue("""
                {"fullName":"Ali","email":"ali@x.com","password":"password123",
                 "termsAccepted":true}
                """, RegisterRequest.class);

        assertThat(violatedFields(request)).contains("termsVersion");
    }

    @Test
    @DisplayName("a blank version is reported: accepting without saying what is not accepting")
    void blankVersionIsRejected() {
        RegisterRequest request = read("true", "\"   \"");

        assertThat(violatedFields(request)).contains("termsVersion");
    }

    private RegisterRequest read(String termsAccepted, String termsVersion) {
        return json.readValue("""
                {"fullName":"Ali","email":"ali@x.com","password":"password123",
                 "termsAccepted":%s,"termsVersion":%s}
                """.formatted(termsAccepted, termsVersion), RegisterRequest.class);
    }

    private java.util.Set<String> violatedFields(RegisterRequest request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }
}
