package com.manara.backend.contact.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where a public contact-form submission is delivered.
 *
 * <p>Deliberately its own property rather than reusing {@code email.reply-to}: that one's
 * documented job is the Reply-To header on automated mail, and overloading it as "the inbox that
 * reads new contact submissions" would make changing either purpose a silent change to the other.
 * Defaults to {@code email.reply-to} in {@code application.properties} so nothing is unconfigured
 * out of the box, but an operator can point it at a different mailbox without touching that.
 *
 * @param recipientEmail the mailbox a contact submission is sent to
 */
@ConfigurationProperties(prefix = "app.contact")
public record ContactProperties(String recipientEmail) {
}
