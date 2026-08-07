package com.manara.backend.email.model;

import lombok.Builder;

import java.util.List;

/**
 * A provider-independent outgoing email.
 *
 * <p>Business features describe <em>what</em> to send by building this model; the email feature
 * decides <em>how</em> it is delivered. Nothing here is specific to a mail provider, and the sender
 * is deliberately absent — it belongs to the email feature's configuration, not to callers.
 *
 * @param to      recipient address (required)
 * @param subject subject line (required)
 * @param html    HTML body (required)
 * @param text    plain-text alternative, or {@code null}
 * @param replyTo per-message Reply-To override; when {@code null} the configured default applies
 * @param inlineImages images embedded in the body and referenced as {@code cid:<contentId>}; never
 *                     {@code null} — absent means an empty list
 */
@Builder
public record EmailMessage(
        String to,
        String subject,
        String html,
        String text,
        String replyTo,
        List<InlineImage> inlineImages
) {

    public EmailMessage {
        requireText(to, "to");
        requireText(subject, "subject");
        requireText(html, "html");
        inlineImages = inlineImages == null ? List.of() : List.copyOf(inlineImages);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("EmailMessage." + field + " must not be blank");
        }
    }
}
