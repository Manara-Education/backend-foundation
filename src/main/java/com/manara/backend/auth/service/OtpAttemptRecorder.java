package com.manara.backend.auth.service;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.repository.OtpRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a failed OTP verification so the count survives the rejection.
 *
 * <p>This exists as its own bean for one reason, and it is the whole point of the class:
 * {@code OtpService.validateCode} signals a wrong code by throwing, and a thrown
 * {@link RuntimeException} rolls the surrounding transaction back — taking the incremented
 * attempt counter with it. An attacker would then get unlimited guesses while the counter sat
 * permanently at zero.
 *
 * <p>{@link Propagation#REQUIRES_NEW} commits the increment in its own transaction before the
 * caller throws, so the count sticks. It has to live in a separate bean because Spring's
 * transaction proxy is bypassed by self-invocation — calling a {@code REQUIRES_NEW} method from
 * another method of the same class would silently do nothing.
 */
@Component
@RequiredArgsConstructor
public class OtpAttemptRecorder {

    private final OtpRepository otpRepository;

    /**
     * Increments the failure count for this code and returns the new total. When the allowance is
     * exhausted the code is also marked used, which burns it — the holder must request a new one
     * rather than continuing to guess this one.
     *
     * @return the number of failed attempts recorded against the code, including this one
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long otpId, int maxAttempts) {
        Otp otp = otpRepository.findById(otpId).orElse(null);
        if (otp == null) {
            return maxAttempts;
        }

        int attempts = otp.getAttempts() + 1;
        otp.setAttempts(attempts);
        if (attempts >= maxAttempts) {
            otp.setUsed(true);
        }
        otpRepository.save(otp);
        return attempts;
    }
}
