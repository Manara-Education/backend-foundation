package com.manara.backend.email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider-independent email settings.
 *
 * <p>The sender belongs to the email feature, not to the features that send mail: no caller has to
 * know or repeat the from-address, and changing it is a configuration change.
 *
 * @param from    default sender applied to every outgoing message
 * @param replyTo default Reply-To, so automated mail from {@code no-reply@} still reaches a human
 */
@ConfigurationProperties(prefix = "email")
public record EmailProperties(
        Sender from,
        String replyTo
) {

    public record Sender(String address, String name) {

        /**
         * RFC 5322 sender, e.g. {@code Manara <no-reply@manara.com>}; falls back to the bare address
         * when no display name is configured.
         */
        public String formatted() {
            return (name == null || name.isBlank()) ? address : name + " <" + address + ">";
        }
    }
}
