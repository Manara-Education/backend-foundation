package com.manara.backend.common.exception;

import lombok.Getter;

/**
 * A request the domain refuses. Answered {@code 400} with a localized message.
 *
 * <p>{@link #errorCode} is optional and additive. Most refusals only ever need prose; a few are
 * conditions a client has to branch on, and those carry a stable {@link ErrorCode} alongside the
 * message so the branch is not a string comparison against a translated sentence.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String messageCode;
    private final Object[] args;

    /** {@code null} unless this condition is one a client is expected to recognise. */
    private final ErrorCode errorCode;

    public BusinessException(String messageCode, Object... args) {
        this(null, messageCode, args);
    }

    public BusinessException(ErrorCode errorCode, String messageCode, Object... args) {
        super(messageCode);
        this.messageCode = messageCode;
        this.args = args;
        this.errorCode = errorCode;
    }
}
