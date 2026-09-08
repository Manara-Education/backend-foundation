package com.manara.backend.terms.model;

import java.time.LocalDate;

/**
 * One published revision of the Terms and Conditions: its identifier and the date it took effect.
 *
 * <p>A value, not a table. Versions are published by deploying, not by writing a row, so there is
 * nothing here for an operator to edit at runtime and nothing that can be out of step between two
 * instances of the same build. What <em>is</em> stored is the acceptance — {@link TermsAcceptance}
 * — which names a version by {@link #id()}.
 *
 * <p>{@code id} is opaque to every caller. The frontend keys its stored text by it and never parses
 * it, so a future version may be called {@code "2.0"}, {@code "2026-11"} or anything else without a
 * client changing. Nothing in the application compares two ids for ordering; "current" is a
 * property of the registry, not something derivable from the string.
 */
public record TermsVersion(String id, LocalDate effectiveDate) {
}
