package com.manara.backend.auth.service;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The attempt counter, against a real database.
 *
 * <p>These three behaviours were previously pinned with a mocked repository, which was a reasonable
 * shape while the increment was computed in Java. It no longer is: the count is incremented, the
 * ceiling judged and the code burned by a single SQL statement, precisely so that two concurrent
 * failures cannot both read the same value and write the same total. A mock of the repository would
 * now be asserting that the code calls a method — the one thing that was never in doubt — while the
 * behaviour under test happens inside PostgreSQL.
 *
 * <p>So the same three cases are made here, unchanged in substance, against the real table. What one
 * failure does to a fresh code, what the last permitted failure does, and what a failure against a
 * code that has since vanished returns. The concurrent behaviour these were rewritten for lives in
 * {@code OtpConcurrencyTest}.
 */
class OtpAttemptRecorderTest extends AbstractPostgresBackedTest {

    private static final int MAX_ATTEMPTS = 5;
    private static final String DOMAIN = "@attemptrecorder.example";
    private static final String EMAIL = "counter" + DOMAIN;

    @Autowired
    private OtpAttemptRecorder recorder;

    @Autowired
    private OtpRepository otpRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private User account;

    @BeforeEach
    void createAccount() {
        removeTestData();
        account = userRepository.save(User.builder()
                .fullName("Counter Test")
                .email(EMAIL)
                .password("$2a$10$irrelevant")
                .role(Role.STUDENT)
                .emailVerified(true)
                .build());
    }

    @AfterEach
    void removeTestData() {
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    void countsAFailureAndLeavesTheCodeUsableWhileAllowanceRemains() {
        Otp otp = code(0);

        assertThat(recorder.recordFailure(otp.getId(), MAX_ATTEMPTS)).isEqualTo(1);

        assertThat(attemptsOf(otp.getId())).isEqualTo(1);
        assertThat(usedOf(otp.getId())).isFalse();
    }

    /**
     * The final failure burns the code. Leaving it usable would mean the ceiling only slowed an
     * attacker down rather than stopping them — they could keep guessing the same live code.
     */
    @Test
    void theFinalFailureMarksTheCodeUsedSoItCannotBeGuessedFurther() {
        Otp otp = code(MAX_ATTEMPTS - 1);

        assertThat(recorder.recordFailure(otp.getId(), MAX_ATTEMPTS)).isEqualTo(MAX_ATTEMPTS);

        assertThat(usedOf(otp.getId())).isTrue();
    }

    /** A code that has vanished between lookup and record is treated as exhausted, not as free. */
    @Test
    void aMissingCodeIsTreatedAsExhausted() {
        assertThat(recorder.recordFailure(-1L, MAX_ATTEMPTS)).isEqualTo(MAX_ATTEMPTS);
    }

    private Otp code(int attempts) {
        return otpRepository.save(Otp.builder()
                .user(account)
                .code("123456")
                .type(OtpType.EMAIL_VERIFICATION)
                .used(false)
                .attempts(attempts)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build());
    }

    private int attemptsOf(Long otpId) {
        return jdbc.queryForObject("SELECT attempts FROM otps WHERE id = ?", Integer.class, otpId);
    }

    private boolean usedOf(Long otpId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT used FROM otps WHERE id = ?", Boolean.class, otpId));
    }
}
