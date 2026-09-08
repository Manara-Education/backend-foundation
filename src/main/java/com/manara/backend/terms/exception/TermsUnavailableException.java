package com.manara.backend.terms.exception;

import lombok.Getter;

/**
 * Raised when the application cannot say which version of the Terms and Conditions is current.
 *
 * <p>Answered {@code 503}, not {@code 400} or {@code 500}: nothing is wrong with the caller's
 * request, and the condition is transient in the only sense that matters — a deployment that can
 * name a current version fixes it. Retrying later is the correct client behaviour, which is exactly
 * what {@code 503} says and what {@code 400} would not.
 *
 * <p>It exists so that "the registry could not answer" can never quietly become "consent was not
 * required". A registration that reaches this is refused with nothing written, which is the safe
 * side of a question the server cannot answer.
 *
 * <p>Follows the same shape as {@code EmailDeliveryException}: an i18n message code resolved by
 * {@code GlobalExceptionHandler}, never prose built here. The handler pairs it with
 * {@code ErrorCode.TERMS_UNAVAILABLE} so a client can branch on the condition.
 */
@Getter
public class TermsUnavailableException extends RuntimeException {

    private final String messageCode;
    private final Object[] args;

    public TermsUnavailableException(String messageCode, Object... args) {
        super(messageCode);
        this.messageCode = messageCode;
        this.args = args;
    }
}
