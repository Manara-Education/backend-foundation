package com.manara.backend.email.model;

/**
 * Provider-independent outcome of an accepted send.
 *
 * <p>{@code messageId} is whatever identifier the provider assigned to the accepted message. It is
 * useful for correlating application logs with the provider's delivery dashboard. Acceptance is not
 * delivery — the provider has taken responsibility for the message, nothing more.
 */
public record EmailSendResult(String messageId) {
}
