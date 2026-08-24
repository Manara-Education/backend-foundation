package com.manara.backend.common.json;

import com.manara.backend.common.util.EmailAddress;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Canonicalises an email field as it is read off the wire, before anything else sees it.
 *
 * <p>Applied with {@code @JsonDeserialize(using = CanonicalEmailDeserializer.class)} on the email
 * field of every request DTO that carries one.
 *
 * <p>Deserialisation, rather than the service, is the right moment for two reasons. The obvious one
 * is that it keeps the rule out of controllers and out of every service that happens to receive an
 * address. The less obvious one is ordering: {@code @Valid} runs after Jackson has built the
 * object, so a padded address such as {@code "  Ali@x.com  "} reaches
 * {@link jakarta.validation.constraints.Email} already trimmed. Left untrimmed it is rejected
 * outright — surrounding spaces are not legal in an address — and the caller is told their
 * perfectly valid address is invalid.
 *
 * <p>This is Jackson 3 ({@code tools.jackson}), which is what Spring Boot 4 uses. The Jackson 2
 * equivalents under {@code com.fasterxml.jackson.databind} still resolve on this classpath, because
 * other libraries drag Jackson 2 in transitively — but the mapper Spring MVC actually binds request
 * bodies with is the Jackson 3 one, and it ignores Jackson 2 annotations completely. Extending the
 * wrong base class here compiles, deploys, and quietly does nothing.
 *
 * <p>A blank value is deliberately left blank rather than turned into {@code null}: the field's own
 * {@code @NotBlank} owns that case and produces the project's standard validation response.
 */
public class CanonicalEmailDeserializer extends ValueDeserializer<String> {

    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        return EmailAddress.canonical(parser.getValueAsString());
    }
}
