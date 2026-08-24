package com.manara.backend.user.repository;

import com.manara.backend.user.model.User;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<@NonNull User, @NonNull Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Whether the account still owes a password change, read straight from the row.
     *
     * The security filter asks this on every authenticated request rather than trusting the
     * principal it finds in the session: that principal is a snapshot serialised at sign-in, so
     * it still says "reset required" after the password has actually been changed, and still
     * says "no reset required" for a session opened before an operator flagged the account.
     * The database is the only copy of this flag anything is allowed to believe.
     */
    boolean existsByIdAndRequiresPasswordResetTrue(Long id);
}
