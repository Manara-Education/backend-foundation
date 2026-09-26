package com.manara.backend.common.exception;

import java.time.Duration;

/**
 * A refusal the caller can overcome by waiting: answered 429 with {@code Retry-After}.
 *
 * <p>For limits the domain enforces on a specific resource — a code that may not be resent yet, a
 * request locked after too many wrong codes — as opposed to the per-address request budgets
 * {@code RateLimitFilter} applies before anything reaches a controller.
 */
public class TooManyRequestsException extends BusinessException {

    private final Duration retryAfter;

    public TooManyRequestsException(ErrorCode errorCode, Duration retryAfter, String messageCode, Object... args) {
        super(errorCode, messageCode, args);
        this.retryAfter = retryAfter == null || retryAfter.isNegative() ? Duration.ZERO : retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
