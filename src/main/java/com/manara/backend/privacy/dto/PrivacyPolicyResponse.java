package com.manara.backend.privacy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/**
 * The published privacy policy, as {@code GET /api/v1/privacy-policy/current} returns it.
 *
 * <p>Plain text throughout. No field carries HTML or Markdown, so the client renders every value
 * as text and there is nothing to sanitise.
 *
 * @param policyId      always {@code privacy-policy}; stable across versions
 * @param version       opaque version id; never parsed or ordered by a client
 * @param effectiveDate ISO-8601 date the version took effect
 * @param supersedes    the version this one replaced, or absent for the first
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PrivacyPolicyResponse(
        String policyId,
        String language,
        String version,
        LocalDate effectiveDate,
        String supersedes,
        String title,
        String summary,
        Operator operator,
        List<Section> sections) {

    public record Operator(String name, String owner, String privacyEmail) {
    }

    public record Section(String id, String number, String title, List<Block> blocks) {
    }

    /** {@code paragraph} → {@code text}; {@code list} → {@code items}; {@code email} → {@code label}, {@code address}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Block(String type, String text, List<String> items, String label, String address) {
    }
}
