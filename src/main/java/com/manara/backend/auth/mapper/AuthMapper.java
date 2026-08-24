package com.manara.backend.auth.mapper;

import com.manara.backend.auth.dto.AuthResponse;
import com.manara.backend.auth.dto.RegisterRequest;
import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    /**
     * Builds the account row from a registration request.
     *
     * <p>The address is canonicalised here rather than trusted from the request. Requests already
     * arrive canonical — {@code CanonicalEmailDeserializer} sees to that — but this is the only
     * place in the application that constructs a {@code User}, and Lombok's builder writes fields
     * directly rather than through {@code User#setEmail}. Doing it here is what makes "the column
     * only ever holds the canonical form" true of every row, not merely of rows that came in over
     * HTTP.
     */
    public User toUser(RegisterRequest request, String encodedPassword, Role role) {
        return User.builder()
                .fullName(request.getFullName())
                .email(EmailAddress.canonical(request.getEmail()))
                .password(encodedPassword)
                .role(role)
                .build();
    }

    public AuthResponse toAuthResponse(User user) {
        return AuthResponse.builder()
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .requiresPasswordReset(user.isRequiresPasswordReset())
                .build();
    }
}
