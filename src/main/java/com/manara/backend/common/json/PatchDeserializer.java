package com.manara.backend.common.json;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a string field into a {@link Patch}, keeping "absent" apart from "explicitly null".
 *
 * <p>Three overrides, one for each state Jackson can be in about a property:
 *
 * <ul>
 *   <li>{@code deserialize} — the JSON had a value. Present, with it.
 *   <li>{@code getNullValue} — the JSON had {@code null}. Present, with {@code null}: the client
 *       asked for the field to be cleared, and that is not the same as saying nothing.
 *   <li>{@code getAbsentValue} — the JSON had no such key. Java {@code null}, which
 *       {@link Patch#isPresent} reads as absent. This is the override that matters: a
 *       properties-based creator supplies a value for every parameter, and without this Jackson
 *       falls back to the null value above and every omitted field would arrive looking like an
 *       explicit clear.
 * </ul>
 *
 * <p>This is Jackson 3 ({@code tools.jackson}), which is what Spring Boot 4 binds request bodies
 * with. The Jackson 2 base class of the same shape still resolves on this classpath and is ignored
 * at runtime — see {@code CanonicalEmailDeserializer} for the same warning.
 */
public class PatchDeserializer extends ValueDeserializer<Patch<String>> {

    @Override
    public Patch<String> deserialize(JsonParser parser, DeserializationContext context) {
        return Patch.of(parser.getValueAsString());
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
