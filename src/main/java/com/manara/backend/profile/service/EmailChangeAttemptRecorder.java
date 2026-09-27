package com.manara.backend.profile.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts a wrong code, in its own transaction.
 *
 * <p>The refusal that follows a wrong code rolls the caller's transaction back; counting in that
 * transaction would discard the count and give unlimited guesses. One statement increments, locks at
 * the ceiling and reports the result, as {@code OtpAttemptRecorder} does for sign-up codes.
 */
@Component
@RequiredArgsConstructor
public class EmailChangeAttemptRecorder {

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int recordFailure(Long id, int maxAttempts) {
        Object attempts = entityManager.createNativeQuery("""
                        UPDATE email_change_requests
                           SET attempts = attempts + 1,
                               status = CASE WHEN status = 'PENDING' AND attempts + 1 >= :maxAttempts
                                             THEN 'LOCKED' ELSE status END
                         WHERE id = :id
                     RETURNING attempts
                        """)
                .setParameter("id", id)
                .setParameter("maxAttempts", maxAttempts)
                .getResultList()
                .stream()
                .findFirst()
                .orElse(null);
        return attempts == null ? maxAttempts : ((Number) attempts).intValue();
    }
}
