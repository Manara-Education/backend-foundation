package com.manara.backend.terms.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * The version of the Terms and Conditions currently in force, as the client is told it.
 *
 * <p>Two fields, and no text. The document itself lives in the frontend, keyed by {@code version},
 * so this endpoint stays a few dozen bytes and the wording of the terms is not something an API
 * response has to carry, translate or escape.
 *
 * <p>{@code version} is opaque — the client stores it and sends it back, and never parses or
 * compares it. {@code effectiveDate} is displayed alongside the text so a reader can see which
 * revision they are looking at.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class TermsVersionResponse {

    private String version;

    /** ISO-8601 ({@code 2026-09-07}). A published date, not a timestamp: no zone applies. */
    private LocalDate effectiveDate;
}
