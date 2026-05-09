package com.manara.backend.auth.mapper;

import com.manara.backend.auth.dto.AuthResponse;
import com.manara.backend.auth.dto.RegisterRequest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

@Component
public class AuthMapper {

    public User toUser(RegisterRequest request, String encodedPassword, Role role) {
        return User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .password(encodedPassword)
                .role(role)
                .build();
    }

    public AuthResponse toAuthResponse(User user) {
        return AuthResponse.builder()
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
