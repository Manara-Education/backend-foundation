package com.manara.backend.auth.service;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final OtpRepository otpRepository;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${otp.expiration-minutes}")
    private int expirationMinutes;

    @Transactional
    public Otp generateAndSave(User user, OtpType type) {
        otpRepository.invalidateAllByUserIdAndType(user.getId(), type);

        String code = String.format("%06d", RANDOM.nextInt(1_000_000));

        var otp = Otp.builder()
                .code(code)
                .type(type)
                .expiresAt(LocalDateTime.now().plusMinutes(expirationMinutes))
                .user(user)
                .build();

        otp = otpRepository.save(otp);

        // TODO: Replace with actual email service
        log.info("OTP for {} [{}]: {}", user.getEmail(), type, code);

        return otp;
    }

    @Transactional
    public void verify(String email, String code, OtpType type) {
        var otp = otpRepository
                .findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(email, type)
                .orElseThrow(() -> new BusinessException("auth.otp.noActive"));

        if (otp.isExpired()) {
            throw new BusinessException("auth.otp.expired");
        }

        if (!otp.getCode().equals(code)) {
            throw new BusinessException("auth.otp.invalid");
        }

        otp.setUsed(true);
        otpRepository.save(otp);
    }
}
