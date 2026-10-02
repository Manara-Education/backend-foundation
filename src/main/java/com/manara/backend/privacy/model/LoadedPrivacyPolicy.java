package com.manara.backend.privacy.model;

/** A version together with its validated text, as the registry holds it after startup. */
public record LoadedPrivacyPolicy(PrivacyPolicyVersion version, PrivacyPolicyContent content) {
}
