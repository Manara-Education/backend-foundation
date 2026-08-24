package com.manara.backend.email.exception;

import lombok.Getter;

/**
 * Raised when an email could not be handed over to the mail provider.
 *
 * <p>Follows the project's exception convention: carries an i18n message code resolved by
 * {@code GlobalExceptionHandler}, never a provider-specific message. Provider detail (HTTP status,
 * error name) stays in the cause and in provider-level logs so it never reaches API clients.
 */
@Getter
public class EmailDeliveryException extends RuntimeException {

    private final String messageCode;
    private final Object[] args;

    public EmailDeliveryException(String messageCode, Throwable cause, Object... args) {
        super(messageCode, cause);
        this.messageCode = messageCode;
        this.args = args;
    }

    public EmailDeliveryException(String messageCode, Object... args) {
        super(messageCode);
        this.messageCode = messageCode;
        this.args = args;
    }
}
