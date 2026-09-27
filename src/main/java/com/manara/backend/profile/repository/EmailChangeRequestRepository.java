package com.manara.backend.profile.repository;

import com.manara.backend.profile.model.EmailChangeRequest;
import com.manara.backend.profile.model.EmailChangeStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface EmailChangeRequestRepository extends JpaRepository<@NonNull EmailChangeRequest, @NonNull Long> {

    /** Only ever the caller's own request: another account's request id resolves to nothing. */
    Optional<EmailChangeRequest> findByRequestIdAndUserId(UUID requestId, Long userId);

    Optional<EmailChangeRequest> findTopByUserIdOrderByCreatedAtDesc(Long userId);

    // Not clearAutomatically: the caller holds the account row under lock and goes on to use it.
    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailChangeRequest r set r.status = :superseded
             where r.user.id = :userId and r.status = :pending
            """)
    int supersedePending(@Param("userId") Long userId,
                         @Param("pending") EmailChangeStatus pending,
                         @Param("superseded") EmailChangeStatus superseded);

    /**
     * Spends the request, once. Returns 1 for the caller that spent it and 0 for anyone who lost the
     * race to it, or who arrives after it expired, was replaced or was locked.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update EmailChangeRequest r
               set r.status = com.manara.backend.profile.model.EmailChangeStatus.CONSUMED,
                   r.consumedAt = :now
             where r.id = :id
               and r.status = com.manara.backend.profile.model.EmailChangeStatus.PENDING
               and r.expiresAt > :now
               and r.attempts < :maxAttempts
            """)
    int consume(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts);
}
