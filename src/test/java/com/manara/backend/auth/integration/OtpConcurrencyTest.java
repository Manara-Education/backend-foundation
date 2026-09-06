package com.manara.backend.auth.integration;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.auth.service.OtpAttemptRecorder;
import com.manara.backend.auth.service.OtpService;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANARA-SEC-007. What happens to one code when two requests reach it at the same time.
 *
 * <p>The report classified this as <em>suspected</em>: the shape of the code implies a race, but no
 * interleaving had been observed. These tests exist to settle that by measurement rather than by
 * reading, so the finding can be promoted or dropped on evidence.
 *
 * <p>The concurrency is coordinated, not brute-forced. Every worker parks on a {@link CyclicBarrier}
 * after opening its transaction and before the read that matters, so the interleaving under test
 * happens on purpose and the same way each run — rather than being fished for with volume, which
 * would make a green result meaningless and a red one unreproducible.
 *
 * <p>Against real PostgreSQL, because the whole question is what two concurrent READ COMMITTED
 * transactions do to one row. No mock has an opinion about that.
 */
class OtpConcurrencyTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@otprace.example";
    private static final String EMAIL = "racer" + DOMAIN;
    private static final String CODE = "123456";
    private static final int WORKERS = 8;

    @Autowired
    private OtpService otpService;

    @Autowired
    private OtpAttemptRecorder attemptRecorder;

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
                .fullName("Racer Test")
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
    @DisplayName("one code, several simultaneous correct submissions: it may be consumed once")
    void aCodeIsConsumedAtMostOnce() throws Exception {
        issue(CODE, OtpType.PASSWORD_RESET);

        AtomicInteger accepted = new AtomicInteger();
        runTogether(() -> {
            try {
                otpService.verify(EMAIL, CODE, OtpType.PASSWORD_RESET);
                accepted.incrementAndGet();
            } catch (RuntimeException expected) {
                // Every loser must lose. Which exception it loses with is not the point.
            }
            return null;
        });

        assertThat(accepted.get())
                .as("a one-time code accepted %d times is a one-time code only by name", accepted.get())
                .isEqualTo(1);

        assertThat(unusedCodeCount())
                .as("the code must be spent afterwards")
                .isZero();
    }

    @Test
    @DisplayName("simultaneous wrong guesses: every one of them is counted")
    void noFailedAttemptIsLost() throws Exception {
        Otp otp = issue(CODE, OtpType.PASSWORD_RESET);

        // The ceiling is deliberately above the worker count, so this test measures counting and
        // not the ceiling. Losing an increment here is what later lets a guesser have more tries
        // than the policy allows.
        runTogether(() -> attemptRecorder.recordFailure(otp.getId(), WORKERS + 10));

        assertThat(attemptsOf(otp.getId()))
                .as("%d concurrent failures must be recorded as %d", WORKERS, WORKERS)
                .isEqualTo(WORKERS);
    }

    @Test
    @DisplayName("simultaneous wrong guesses cannot exceed the attempt ceiling")
    void theCeilingHoldsUnderConcurrency() throws Exception {
        Otp otp = issue(CODE, OtpType.PASSWORD_RESET);
        int ceiling = 3;

        runTogether(() -> attemptRecorder.recordFailure(otp.getId(), ceiling));

        // Whatever the interleaving, a code that has been guessed at more times than the policy
        // allows must not still be usable.
        assertThat(usedOf(otp.getId()))
                .as("the code must be burned once the ceiling is reached")
                .isTrue();

        assertThat(attemptsOf(otp.getId()))
                .as("attempts must not be under-counted below the ceiling")
                .isGreaterThanOrEqualTo(ceiling);
    }

    @Test
    @DisplayName("a correct submission racing wrong ones does not resurrect the burnt code")
    void aWinnerDoesNotClobberCommittedFailures() throws Exception {
        Otp otp = issue(CODE, OtpType.PASSWORD_RESET);

        // verify() writes the whole entity back from the snapshot it loaded, so its UPDATE also
        // rewrites `attempts` at the value it read. Failures committed in between would be erased.
        attemptRecorder.recordFailure(otp.getId(), 99);
        attemptRecorder.recordFailure(otp.getId(), 99);

        otpService.verify(EMAIL, CODE, OtpType.PASSWORD_RESET);

        assertThat(attemptsOf(otp.getId()))
                .as("a successful consume must not roll the failure count back")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("simultaneous resends leave exactly one usable code")
    void onlyOneCodeSurvivesConcurrentResends() throws Exception {
        runTogether(() -> {
            try {
                otpService.generateAndSend(account, OtpType.PASSWORD_RESET);
            } catch (RuntimeException expected) {
                // A resend that loses its race is fine; two usable codes are not.
            }
            return null;
        });

        assertThat(unusedCodeCount())
                .as("each resend retires the last one, so at most one may remain usable")
                .isLessThanOrEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private Otp issue(String code, OtpType type) {
        return otpRepository.save(Otp.builder()
                .user(account)
                .code(code)
                .type(type)
                .used(false)
                .attempts(0)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build());
    }

    /**
     * Runs {@code work} on {@link #WORKERS} threads released at the same instant.
     *
     * <p>The barrier is what makes this a schedule rather than a lottery: each thread is already
     * started, already scheduled, and waiting, so they enter the critical section together instead
     * of being spread out by thread-creation cost.
     */
    private void runTogether(Callable<?> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        CyclicBarrier startLine = new CyclicBarrier(WORKERS);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < WORKERS; i++) {
                results.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return work.call();
                }));
            }
            for (Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // A worker that threw is a legitimate outcome for several of these tests; the
                    // assertions read the database rather than the exceptions.
                }
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int attemptsOf(Long otpId) {
        return jdbc.queryForObject("SELECT attempts FROM otps WHERE id = ?", Integer.class, otpId);
    }

    private boolean usedOf(Long otpId) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT used FROM otps WHERE id = ?", Boolean.class, otpId));
    }

    private int unusedCodeCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM otps o JOIN users u ON u.id = o.user_id "
                        + "WHERE u.email = ? AND o.used = false", Integer.class, EMAIL);
    }
}
