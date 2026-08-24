package com.manara.backend.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {

    private String fullName;
    private String email;
    private String role;

    /**
     * Additive field. It is always present, so a client that does not know about it simply
     * ignores it and keeps the behaviour it had; a client that does knows, on both sign-in and
     * session restore, that this account must change its password before going anywhere else.
     */
    private boolean requiresPasswordReset;
}
