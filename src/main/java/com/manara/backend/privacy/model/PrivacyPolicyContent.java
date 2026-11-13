package com.manara.backend.privacy.model;

import java.util.List;

/**
 * The text of one privacy-policy version, exactly as it is stored in its classpath resource.
 *
 * <p>Structured rather than HTML or Markdown on purpose. Every value is plain text, so no client
 * ever has to sanitise markup that arrived from an API, and a reviewer reads the same words the
 * page will render. The few things that are not prose — the contact address — are typed blocks the
 * client renders itself.
 *
 * <p>Nothing here says whether the version is published or when it took effect. That is
 * publication metadata, held by {@link PrivacyPolicyVersion}, so the content file of a published
 * version never has to be touched again.
 */
public record PrivacyPolicyContent(
        String policyId,
        String version,
        String language,
        String title,
        String summary,
        Operator operator,
        List<Section> sections) {

    /** Who runs the platform and where privacy questions go, as the owner approved them. */
    public record Operator(String name, String owner, String privacyEmail) {
    }

    /**
     * One numbered section. {@code id} is a stable anchor: it is what a link to
     * {@code /privacy#retention} points at, so it survives renumbering and new versions.
     */
    public record Section(String id, String number, String title, List<Block> blocks) {
    }

    /**
     * A piece of a section. Flat rather than polymorphic so the JSON stays readable to a
     * non-engineer reviewing the text; which fields a type requires is enforced by
     * {@code PrivacyPolicyContentValidator}.
     *
     * <ul>
     *   <li>{@code paragraph} — {@code text}</li>
     *   <li>{@code list} — {@code items}</li>
     *   <li>{@code email} — {@code label} and {@code address}</li>
     * </ul>
     */
    public record Block(String type, String text, List<String> items, String label, String address) {
    }
}
