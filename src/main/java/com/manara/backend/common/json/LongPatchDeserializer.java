package com.manara.backend.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * {@link PatchDeserializer} for an id: a JSON integer (or a string holding one) is a value, JSON
 * {@code null} is an explicit clear, and an absent key stays absent. Anything else is refused as an
 * unreadable body rather than coerced.
 */
public class LongPatchDeserializer extends ValueDeserializer<Patch<Long>> {

    @Override
    public Patch<Long> deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return Patch.of(parser.getLongValue());
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING && parser.getString().matches("\\d{1,18}")) {
            return Patch.of(Long.parseLong(parser.getString()));
        }
        return context.reportInputMismatch(Long.class, "Expected an integer id");
    }

    @Override
    public Object getNullValue(DeserializationContext context) {
        return Patch.of(null);
    }

    @Override
    public Object getAbsentValue(DeserializationContext context) {
        return null;
    }
}
