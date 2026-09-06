package com.manara.backend.auth.repository;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OtpRepository extends JpaRepository<@NonNull Otp,@NonNull Long> {

    Optional<Otp> findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
            String email, OtpType type);

    @Modifying
    @Query("UPDATE Otp o SET o.used = true WHERE o.user.id = :userId AND o.type = :type AND o.used = false")
    void invalidateAllByUserIdAndType(@Param("userId") Long userId, @Param("type") OtpType type);

    /**
     * Spends a code, if it is still there to be spent.
     *
     * <p>Returns the number of rows it changed: 1 for the caller that consumed it, 0 for a caller
     * that arrived after somebody else had. That return value is the whole point. Reading a row,
     * deciding in Java that it is unused, and then writing {@code used = true} lets two requests
     * both read the same unused row and both succeed, because nothing between the read and the
     * write says the row must still be as it was read. Here the condition and the write are the
     * same statement, so PostgreSQL settles it: the second UPDATE re-evaluates {@code used = false}
     * against the committed row and matches nothing.
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Otp o SET o.used = true WHERE o.id = :id AND o.used = false")
    int consume(@Param("id") Long id);
}
