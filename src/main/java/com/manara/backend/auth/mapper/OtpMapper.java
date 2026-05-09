package com.manara.backend.auth.mapper;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OtpMapper {

    public Otp toOtp(User user, String code, OtpType type, LocalDateTime expiresAt) {
        return Otp.builder()
                .code(code)
                .type(type)
                .expiresAt(expiresAt)
                .user(user)
                .build();
    }
}
