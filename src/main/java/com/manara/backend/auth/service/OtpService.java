package com.manara.backend.auth.service;

import com.manara.backend.auth.email.OtpEmailFactory;
import com.manara.backend.auth.mapper.OtpMapper;
import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.email.service.EmailService;
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
    private final OtpEmailFactory otpEmailFactory;
    private final EmailService emailService;
    private final OtpAttemptRecorder attemptRecorder;

    @Value("${otp.expiration-minutes}")
    private int expirationMinutes;

    /**
     * Guesses allowed against a single code before it is burned.
     *
     * <p>A six-digit code has a million possibilities, so five attempts leave a 1-in-200,000
     * chance of a lucky guess per issued code — while comfortably tolerating a user mistyping a
     * code they legitimately received.
     */
    @Value("${otp.max-attempts:5}")
    private int maxAttempts;

    /**
     * Invalidates any outstanding code of this type, issues a new one, and emails it.
     *
     * <p>The generated code is never returned, logged, or exposed by any API — it exists only in
     * this method and in the message handed to the email feature.
     *
     * <p>Delivery is synchronous and runs inside the caller's transaction. That is deliberate: if
     * the provider rejects the message the transaction rolls back, so callers such as registration
     * never leave behind an unverified account whose owner received no code. The cost is that a
     * database connection is held for the duration of an outbound HTTP call, and the Resend SDK
     * exposes no timeout configuration (OkHttp's ~10s defaults apply). Acceptable at OTP volumes;
     * revisit with asynchronous dispatch if email traffic grows beyond authentication flows.
     */
    @Transactional
    public void generateAndSend(User user, OtpType type) {
        otpRepository.invalidateAllByUserIdAndType(user.getId(), type);

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        var expiresAt = LocalDateTime.now().plusMinutes(expirationMinutes);
        otpRepository.save(otpMapper.toOtp(user, code, type, expiresAt));

        emailService.send(otpEmailFactory.create(user.getEmail(), code, type, expirationMinutes));
    }

    /**
     * Resolves the caller's outstanding code and checks the one they supplied against it.
     *
     * <p>The address is canonicalised before the lookup. Callers hand this whatever string the
     * client sent, and {@code otps} is joined to {@code users} on the stored — canonical —
     * address, so an uncanonicalised argument would find no outstanding code and report
     * {@code auth.otp.noActive}: a user who registered as {@code Ali@x.com} and typed
     * {@code ali@x.com} into the verification form would be told their code had expired.
     *
     * <p>A wrong code is counted. Once {@code otp.max-attempts} failures accumulate against the
     * same code it is marked used, so guessing must start over from a newly emailed code rather
     * than continuing against the one already in flight. Without this, a six-digit code with a
     * ten-minute lifetime and no attempt ceiling is enumerable.
     */
    public Otp validateCode(String email, String code, OtpType type) {
        var otp = otpRepository
                .findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
                        EmailAddress.canonical(email), type)
                .orElseThrow(() -> new BusinessException("auth.otp.noActive"));

        if (otp.isExpired()) {
            throw new BusinessException("auth.otp.expired");
        }

        if (!otp.getCode().equals(code)) {
            // Committed in its own transaction, because throwing below rolls this one back and
            // would otherwise discard the increment — leaving the attacker unlimited guesses.
            int attempts = attemptRecorder.recordFailure(otp.getId(), maxAttempts);
            if (attempts >= maxAttempts) {
                throw new BusinessException("auth.otp.tooManyAttempts");
            }
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
