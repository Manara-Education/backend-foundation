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
}
