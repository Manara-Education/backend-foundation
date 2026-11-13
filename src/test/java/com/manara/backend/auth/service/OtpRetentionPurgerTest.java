package com.manara.backend.auth.service;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seven-day retention of OTP records, against real PostgreSQL.
 *
 * <p>Ages are set with SQL after insert, because {@code createdAt} is written by {@code @PrePersist}
 * and is not updatable through JPA.
 */
class OtpRetentionPurgerTest extends AbstractPostgresBackedTest {

    @Autowired
    private OtpRetentionPurger purger;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${otp.expiration-minutes}")
    private int expirationMinutes;

    @Value("${otp.record-retention-days}")
    private int retentionDays;

    @Test
    @DisplayName("retention is 7 days and code validity is still 10 minutes: two separate settings")
    void retentionDoesNotChangeValidity() {
        assertThat(retentionDays).isEqualTo(7);
        assertThat(expirationMinutes).isEqualTo(10);
    }

    @Test
    @DisplayName("rows older than 7 days are deleted, used or not; younger rows stay")
    void deletesOnlyRowsPastRetention() {
        User user = userRepository.save(User.builder()
                .fullName("Retention Test")
                .email("otp-retention-" + UUID.randomUUID() + "@example.com")
                .password("x")
                .build());

        Long oldUsed = otp(user, OtpType.EMAIL_VERIFICATION, true, LocalDateTime.now().minusDays(8));
        Long oldUnused = otp(user, OtpType.EMAIL_VERIFICATION, false, LocalDateTime.now().minusDays(7).minusMinutes(5));
        Long justInside = otp(user, OtpType.EMAIL_VERIFICATION, true, LocalDateTime.now().minusDays(6).minusHours(23));
        Long fresh = otp(user, OtpType.PASSWORD_RESET, false, LocalDateTime.now());

        int deleted = purger.purgeExpiredRecords();

        assertThat(deleted).isGreaterThanOrEqualTo(2);
        List<Long> remaining = otpRepository.findAllById(List.of(oldUsed, oldUnused, justInside, fresh))
                .stream().map(Otp::getId).toList();
        assertThat(remaining).containsExactlyInAnyOrder(justInside, fresh);
        assertThat(userRepository.findById(user.getId())).isPresent();
    }

    private Long otp(User user, OtpType type, boolean used, LocalDateTime createdAt) {
        Otp saved = otpRepository.saveAndFlush(Otp.builder()
                .code("123456")
                .type(type)
                .used(used)
                .expiresAt(createdAt.plusMinutes(10))
                .user(user)
                .build());
        jdbc.update("UPDATE otps SET created_at = ? WHERE id = ?", createdAt, saved.getId());
        return saved.getId();
    }
}
