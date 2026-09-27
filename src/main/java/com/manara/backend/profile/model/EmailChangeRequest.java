package com.manara.backend.profile.model;

import com.manara.backend.user.model.User;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * A signed-in account's request to move to a new address, waiting for the code sent there.
 *
 * <p>Holds an HMAC of the code, never the code. {@code resendCount} is the code's generation: it is
 * part of what the HMAC covers, so a resend makes every earlier code for the request worthless.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "email_change_requests")
public class EmailChangeRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "new_email", nullable = false, updatable = false)
    private String newEmail;

    @Column(name = "code_hash", nullable = false, length = 64)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EmailChangeStatus status;

    /** The address belonged to another account at request time: answered normally, never sent a code. */
    @Column(name = "target_unavailable", nullable = false, updatable = false)
    private boolean targetUnavailable;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "resend_available_at", nullable = false)
    private LocalDateTime resendAvailableAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EmailChangeRequest other)) return false;
        return requestId != null && requestId.equals(other.requestId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(requestId);
    }
}
