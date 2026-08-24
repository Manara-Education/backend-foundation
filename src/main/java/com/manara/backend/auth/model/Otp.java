package com.manara.backend.auth.model;

import com.manara.backend.user.model.User;
import jakarta.annotation.Nonnull;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Nonnull
@Table(name = "otps")
public class Otp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OtpType type;

    @Builder.Default
    @Column(nullable = false)
    private boolean used = false;

    /**
     * Failed verification attempts against this specific code.
     *
     * <p>Counted on the row rather than in Redis so the limit survives a restart of the session
     * store and stays bound to the code being guessed — an attacker cannot reset it by rotating
     * IP addresses or asking for a new session.
     */
    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
