package com.manara.backend.profile.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What the client needs to show the code step, and nothing that says whether the address exists.
 *
 * @param maskedEmail       the address the code was (or would have been) sent to, partly hidden
 * @param expiresAt         when the current code stops working
 * @param resendAvailableAt the earliest moment a new code may be asked for
 * @param expiresInSeconds  {@code expiresAt} as seconds from the moment of this response — the form a
 *                          client in another time zone, or with a skewed clock, can use as it is
 * @param resendAvailableInSeconds likewise for {@code resendAvailableAt}
 */
public record EmailChangeChallengeResponse(
        UUID requestId,
        String maskedEmail,
        int codeLength,
        LocalDateTime expiresAt,
        LocalDateTime resendAvailableAt,
        long expiresInSeconds,
        long resendAvailableInSeconds) {
}
