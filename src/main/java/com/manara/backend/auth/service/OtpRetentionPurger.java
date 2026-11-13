package com.manara.backend.auth.service;

import com.manara.backend.auth.repository.OtpRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Deletes OTP records once they are older than the retention period the privacy policy states.
 *
 * <p>Retention, not validity. A code stops being accepted after {@code otp.expiration-minutes};
 * the row recording it — code, attempts, timestamps, owning account — is kept a while longer and
 * then removed here, used or not. The two settings are independent, and nothing in this class
 * reads or affects whether a code verifies.
 *
 * <p>The cut-off is computed from the injected {@link Clock} in the same zone {@code Otp#onCreate}
 * writes {@code createdAt} in (the system default), so "seven days" means seven days of wall clock
 * on both sides of the comparison.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpRetentionPurger {

    private final OtpRepository otpRepository;
    private final Clock clock;

    @Value("${otp.record-retention-days}")
    private int retentionDays;

    @Scheduled(cron = "${otp.record-retention-purge-cron}")
    @Transactional
    public int purgeExpiredRecords() {
        if (retentionDays < 1) {
            throw new IllegalStateException("otp.record-retention-days must be at least 1");
        }
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        int deleted = otpRepository.deleteCreatedBefore(cutoff);
        if (deleted > 0) {
            // A count only. No account, no code.
            log.info("Deleted {} OTP record(s) older than {} days", deleted, retentionDays);
        }
        return deleted;
    }
}
