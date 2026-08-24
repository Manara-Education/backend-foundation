package com.manara.backend.course.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Case-insensitive enum parsing for request payloads.
 *
 * <p>The API contract documents lowercase values ({@code "flat"}, {@code "subscription"}) while the
 * codebase's established convention is uppercase enum constants. Rather than reconfiguring Jackson
 * globally — which would change how every existing enum in the application parses — each new enum
 * opts in through a {@code @JsonCreator} delegating here.
 *
 * <p>Unknown values raise {@link IllegalArgumentException}, which Jackson surfaces as a malformed
 * request body and {@code GlobalExceptionHandler} turns into a localized 400.
 */
final class EnumParser {

    private EnumParser() {
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant.name().equalsIgnoreCase(trimmed))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported %s value '%s'. Expected one of %s".formatted(
                                type.getSimpleName(),
                                value,
                                Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", ")))));
    }
}
