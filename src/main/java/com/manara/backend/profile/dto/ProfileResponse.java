package com.manara.backend.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ProfileResponse {

    private String fullName;
    private String email;
    private String role;
    private LocalDateTime createdAt;

    /** Served URL of the account's photo, or {@code null} when it has none. */
    private String avatarUrl;

    private boolean emailVerified;

    /** {@code null} means unknown — the account predates the record — not "never". */
    private LocalDateTime passwordChangedAt;
}
