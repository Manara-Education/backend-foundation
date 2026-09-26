package com.manara.backend.profile.mapper;

import com.manara.backend.profile.dto.EmailChangeChallengeResponse;
import com.manara.backend.profile.model.EmailChangeRequest;
import com.manara.backend.profile.model.EmailChangeStatus;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class EmailChangeMapper {

    public static final int CODE_LENGTH = 6;

    public EmailChangeRequest toEntity(User user, UUID requestId, String newEmail, String codeHash,
                                       boolean targetUnavailable, LocalDateTime now,
                                       LocalDateTime expiresAt, LocalDateTime resendAvailableAt) {
        return EmailChangeRequest.builder()
                .requestId(requestId)
                .user(user)
                .newEmail(newEmail)
                .codeHash(codeHash)
                .status(EmailChangeStatus.PENDING)
                .targetUnavailable(targetUnavailable)
                .attempts(0)
                .resendCount(0)
                .expiresAt(expiresAt)
                .resendAvailableAt(resendAvailableAt)
                .createdAt(now)
                .build();
    }

    public EmailChangeChallengeResponse toResponse(EmailChangeRequest request, LocalDateTime now) {
        return new EmailChangeChallengeResponse(
                request.getRequestId(),
                mask(request.getNewEmail()),
                CODE_LENGTH,
                request.getExpiresAt(),
                request.getResendAvailableAt(),
                secondsUntil(now, request.getExpiresAt()),
                secondsUntil(now, request.getResendAvailableAt()));
    }

    private static long secondsUntil(LocalDateTime now, LocalDateTime deadline) {
        return Math.max(0, Duration.between(now, deadline).toSeconds());
    }

    /** {@code sa***@example.com}: enough to recognise one's own typing, no more. */
    static String mask(String email) {
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        String local = email.substring(0, at);
        String visible = local.substring(0, local.length() <= 2 ? 1 : 2);
        return visible + "***" + email.substring(at);
    }
}
