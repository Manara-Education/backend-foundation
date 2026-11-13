package com.manara.backend.privacy.service;

import com.manara.backend.privacy.model.LoadedPrivacyPolicy;
import com.manara.backend.privacy.model.PrivacyPolicyVersion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every privacy-policy version this build carries, validated, and which one is in force.
 *
 * <p>Built once at startup from {@link PrivacyPolicyCatalog}. Construction loads and checks every
 * version — candidates as well as published ones — and throws if any of them is malformed, if a
 * published text no longer matches its pinned hash, or if the current pointer names a version the
 * catalogue does not have. An application that cannot vouch for its own policy text does not start.
 *
 * <p>{@link #current()} answers only with a <em>published</em> version. While the pointer names a
 * candidate, the answer is empty and the public endpoint says the policy is not published — it
 * never falls back to an older version or to unreviewed text.
 */
public class PrivacyPolicyRegistry {

    private final Map<String, LoadedPrivacyPolicy> versions;
    private final String currentId;

    public PrivacyPolicyRegistry(List<PrivacyPolicyVersion> catalogue, String currentId,
                                 PrivacyPolicyContentLoader loader) {
        Map<String, LoadedPrivacyPolicy> loaded = new LinkedHashMap<>();
        for (PrivacyPolicyVersion version : catalogue) {
            if (loaded.containsKey(version.id())) {
                throw new IllegalStateException("Privacy policy version '" + version.id() + "' is listed twice");
            }
            PrivacyPolicyContentLoader.LoadedContent content = loader.load(version.contentResource());
            List<String> problems = PrivacyPolicyContentValidator.problems(version, content.content(), content.sha256());
            if (!problems.isEmpty()) {
                throw new IllegalStateException("Privacy policy version '" + version.id() + "' is invalid: "
                        + String.join("; ", problems));
            }
            loaded.put(version.id(), new LoadedPrivacyPolicy(version, content.content()));
        }
        for (LoadedPrivacyPolicy entry : loaded.values()) {
            String supersedes = entry.version().supersedes();
            if (supersedes != null && !loaded.containsKey(supersedes)) {
                throw new IllegalStateException("Privacy policy version '" + entry.version().id()
                        + "' supersedes unknown version '" + supersedes + "'");
            }
        }
        if (currentId != null && !loaded.containsKey(currentId)) {
            throw new IllegalStateException("Current privacy policy version '" + currentId + "' is not in the catalogue");
        }
        this.versions = Map.copyOf(loaded);
        this.currentId = currentId;
    }

    /** The version in force, or empty when the current pointer names nothing published. */
    public Optional<LoadedPrivacyPolicy> current() {
        return Optional.ofNullable(currentId)
                .map(versions::get)
                .filter(entry -> entry.version().isPublished());
    }

    /** Any version this build carries, published or not. For tests and diagnostics, never for the API. */
    public Optional<LoadedPrivacyPolicy> find(String id) {
        return Optional.ofNullable(versions.get(id));
    }
}
