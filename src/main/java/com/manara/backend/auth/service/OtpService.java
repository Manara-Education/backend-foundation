package com.manara.backend.auth.service;

import com.manara.backend.auth.mapper.OtpMapper;
import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpRepository otpRepository;
    private final OtpMapper otpMapper;
    private final SecureRandom secureRandom;

    @Value("${otp.expiration-minutes}")
    private int expirationMinutes;

    @Transactional
    public void generateAndSave(User user, OtpType type) {
        otpRepository.invalidateAllByUserIdAndType(user.getId(), type);

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        var expiresAt = LocalDateTime.now().plusMinutes(expirationMinutes);
        otpRepository.save(otpMapper.toOtp(user, code, type, expiresAt));
    }

    public Otp validateCode(String email, String code, OtpType type) {
        var otp = otpRepository
                .findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(email, type)
                .orElseThrow(() -> new BusinessException("auth.otp.noActive"));

        if (otp.isExpired()) {
            throw new BusinessException("auth.otp.expired");
        }

        if (!otp.getCode().equals(code)) {
            throw new BusinessException("auth.otp.invalid");
        }

        return otp;
    }

    @Transactional
    public void verify(String email, String code, OtpType type) {
        var otp = validateCode(email, code, type);
        otp.setUsed(true);
        otpRepository.save(otp);
    }
}
