package com.manara.backend.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Binds a {@code Boolean} field only from a real JSON boolean, and refuses every other shape.
 *
 * <p>Applied with {@code @JsonDeserialize(using = StrictBooleanDeserializer.class)} on a field where
 * the difference between "the client said yes" and "the client sent something that looks a bit like
 * yes" actually matters — consent to the Terms and Conditions being the case it was written for.
 *
 * <p>Jackson's default coercion is generous by design: {@code "true"}, {@code "yes"}, {@code 1} and
 * a handful of other values all bind to {@code true}, and — worse for this purpose — {@code ""},
 * {@code "0"} and {@code 0} all bind to {@code false} without complaint. That is helpful for a
 * preference flag and wrong for a record of agreement. An acceptance must be something the sender
 * meant, expressed in the one way the API documents; anything else is a client bug, and a client bug
 * must not be quietly resolved into a legal claim about a person.
 *
 * <p>A rejected value throws {@code MismatchedInputException}, which Spring's message converter
 * turns into {@code HttpMessageNotReadableException} and the existing handler answers {@code 400}
 * with {@code error.request.malformed}. The parser's own message stays in the logs, as it does for
 * every other unreadable body.
 *
 * <p>JSON {@code null} and an absent property are deliberately <em>not</em> rejected here: they
 * arrive as {@code null} and belong to {@code @NotNull} on the field, which produces the project's
 * ordinary field-error response naming the field. Answering them here instead would replace a
 * message that says which field is missing with one that says the body could not be read.
 *
 * <p>This is Jackson 3 ({@code tools.jackson}), which is what Spring Boot 4 binds request bodies
 * with. The Jackson 2 base class of the same shape still resolves on this classpath because other
 * libraries drag Jackson 2 in transitively, and extending it would compile, deploy, and silently do
 * nothing — see {@code CanonicalEmailDeserializer} for the same warning.
 */
public class StrictBooleanDeserializer extends ValueDeserializer<Boolean> {

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        JsonToken token = parser.currentToken();

        if (token == JsonToken.VALUE_TRUE) {
            return Boolean.TRUE;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return Boolean.FALSE;
        }

        return context.reportInputMismatch(Boolean.class,
                "Expected a JSON boolean (true or false), got %s", token);
    }
}
