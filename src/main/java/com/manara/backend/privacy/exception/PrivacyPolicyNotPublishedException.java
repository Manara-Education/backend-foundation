package com.manara.backend.privacy.exception;

import lombok.Getter;

/**
 * Raised when no published privacy policy is in force.
 *
 * <p>Answered {@code 404} with {@code ErrorCode.PRIVACY_POLICY_NOT_PUBLISHED}: the resource the
 * caller asked for — the published policy — does not exist yet. The client shows that plainly and
 * never substitutes a draft or a copy of its own.
 */
@Getter
public class PrivacyPolicyNotPublishedException extends RuntimeException {

    private final String messageCode;

    public PrivacyPolicyNotPublishedException(String messageCode) {
        super(messageCode);
        this.messageCode = messageCode;
    }
}
