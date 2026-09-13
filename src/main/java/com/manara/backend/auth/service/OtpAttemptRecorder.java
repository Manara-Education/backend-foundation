package com.manara.backend.auth.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts a wrong guess against a code, durably, and burns the code at the ceiling.
 *
 * <p>{@code REQUIRES_NEW} is what makes the count durable: the caller throws immediately afterwards
 * to reject the guess, and that rollback would otherwise discard the increment — leaving a guesser
 * with unlimited tries. That part was always right.
 *
 * <p>What it did not do was make the increment correct when guesses arrive together. The count was
 * read into Java, incremented there, and written back, so two concurrent failures both read
 * {@code n} and both wrote {@code n + 1}: one guess free, and neither request seeing the ceiling it
 * had actually crossed. Under PostgreSQL's default READ COMMITTED there is nothing to prevent that —
 * the second UPDATE re-checks only {@code WHERE id = ?}, which still matches, and writes its stale
 * value over the committed one.
 *
 * <p>So the arithmetic now happens in the database. {@code attempts = attempts + 1} is evaluated
 * against the current committed row, and concurrent updates of the same row serialize on its lock
 * rather than overwriting one another. {@code RETURNING} hands back the value this statement
 * actually produced, so the ceiling is judged on the count as it stands and not on a snapshot.
 */
@Component
@RequiredArgsConstructor
public class OtpAttemptRecorder {

    private final EntityManager entityManager;

    /**
     * Records one failed guess and reports the resulting total.
     *
     * @return the attempt count after this failure, or {@code maxAttempts} when the code no longer
     *         exists — the conservative answer, since a missing code cannot be guessed at further
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long otpId, int maxAttempts) {
        // One statement: increment, decide whether that crosses the ceiling, and report the result.
        // Splitting it in three would put the race back exactly where it was.
        Object attempts = entityManager.createNativeQuery("""
                        UPDATE otps
                           SET attempts = attempts + 1,
                               used = (used OR attempts + 1 >= :maxAttempts)
                         WHERE id = :otpId
                     RETURNING attempts
                        """)
                .setParameter("otpId", otpId)
                .setParameter("maxAttempts", maxAttempts)
                .getResultList()
                .stream()
                .findFirst()
                .orElse(null);

        if (attempts == null) {
            return maxAttempts;
        }
        return ((Number) attempts).intValue();
    }
}
