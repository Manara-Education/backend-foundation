package com.manara.backend.privacy.service;

import com.manara.backend.privacy.model.PrivacyPolicyStatus;
import com.manara.backend.privacy.model.PrivacyPolicyVersion;

import java.util.List;

/**
 * The privacy-policy versions this build ships, and the pointer to the one in force.
 *
 * <h2>How a version is published</h2>
 * <ol>
 *   <li>Its text is a {@link PrivacyPolicyStatus#CANDIDATE} under {@code legal/privacy/}. While it is
 *       a candidate it can be edited freely, and the public endpoint does not serve it.</li>
 *   <li>Publishing is one reviewed change: set the status to {@code PUBLISHED}, set
 *       {@code effectiveDate} to the day the release goes live, and pin {@code contentSha256} (the
 *       validator's failure message prints the value). Release that day.</li>
 *   <li>From then on the file is frozen: any byte changed in it stops the application starting.
 *       A substantive change is a new file, a new id, a new entry here with {@code supersedes} set to
 *       the old id, and {@link #CURRENT_ID} moved to it. Superseded entries stay forever, so
 *       "which policy applied on date X" always has an answer.</li>
 * </ol>
 *
 * <p>{@code effectiveDate} is a fact written down at publication, never {@code LocalDate.now()} —
 * the same rule as {@code TermsVersionRegistry}.
 */
public final class PrivacyPolicyCatalog {

    /** The version the public endpoint serves once it is published. */
    public static final String CURRENT_ID = "1.0";

    /**
     * 1.0 is held as a candidate until the publication blockers recorded in
     * {@code docs/api/PRIVACY_POLICY_API.md} are cleared: until then the text would promise a
     * mailbox and a backup retention that production does not yet have.
     */
    public static final List<PrivacyPolicyVersion> VERSIONS = List.of(
            new PrivacyPolicyVersion(
                    "1.0",
                    "ar",
                    "legal/privacy/privacy-policy-1.0.ar.json",
                    PrivacyPolicyStatus.CANDIDATE,
                    null,
                    null,
                    null));

    private PrivacyPolicyCatalog() {
    }
}
