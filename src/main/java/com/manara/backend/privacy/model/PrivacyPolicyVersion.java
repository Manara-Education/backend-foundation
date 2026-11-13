package com.manara.backend.privacy.model;

import java.time.LocalDate;

/**
 * One version of the privacy policy and its publication metadata.
 *
 * <p>A value, not a table, for the same reason as {@code TermsVersion}: versions are published by
 * deploying, so there is nothing for an operator to edit at runtime and nothing two instances of
 * one build can disagree about. The text lives in {@link #contentResource()} on the classpath.
 *
 * @param id              opaque version id; equals the {@code version} inside the content file
 * @param language        BCP 47 language of the text ({@code ar})
 * @param contentResource classpath location of the {@link PrivacyPolicyContent} JSON
 * @param status          only {@link PrivacyPolicyStatus#PUBLISHED} is ever served
 * @param effectiveDate   the date the version took effect; required once published, absent before
 * @param supersedes      the id of the version this one replaced, or {@code null} for the first
 * @param contentSha256   SHA-256 of the content file, required once published. Startup refuses a
 *                        published version whose file no longer matches: a substantive edit must
 *                        be a new version, never a silent change to one already in force.
 */
public record PrivacyPolicyVersion(
        String id,
        String language,
        String contentResource,
        PrivacyPolicyStatus status,
        LocalDate effectiveDate,
        String supersedes,
        String contentSha256) {

    public boolean isPublished() {
        return status == PrivacyPolicyStatus.PUBLISHED;
    }
}
