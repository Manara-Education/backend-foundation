package com.manara.backend.privacy.model;

/**
 * Whether a privacy-policy version may be shown to the public.
 *
 * <p>{@link #CANDIDATE} is text under review: it ships in the build, is validated like any other
 * version, and is never served. {@link #PUBLISHED} is the only status the public endpoint reads,
 * and a published version's content is pinned by hash so it can no longer change.
 */
public enum PrivacyPolicyStatus {
    CANDIDATE,
    PUBLISHED
}
