package com.manara.backend.user.repository;

import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.user.model.User;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<@NonNull User, @NonNull Long> {

    /**
     * The account owning this address, whatever casing or padding the caller happened to have.
     *
     * <p>A {@code default} method, so Spring Data derives no query from the name and this body runs
     * instead. That is the point: every existing caller already says {@code findByEmail(...)}, and
     * canonicalising here makes all of them case-insensitive at once, without any of them having to
     * remember to normalise first. There is nowhere left to forget.
     *
     * <p>The argument is canonicalised and then matched <em>exactly</em>, rather than compared with
     * a case-insensitive predicate. That is safe because the stored value is guaranteed canonical —
     * V4 normalised the existing rows and {@code ck_users_email_canonical} rejects any future row
     * that is not — so an exact match on the canonical form and a case-insensitive match on the raw
     * form return the same account.
     *
     * <p>It is also the only version that stays fast. Spring Data's {@code IgnoreCase} keyword
     * generates {@code upper(email) = upper(?)}, which no index on this table can answer: every
     * sign-in and every duplicate check would sequentially scan {@code users}. A plain equality uses
     * the unique index on {@code email} directly.
     */
    default Optional<User> findByEmail(String email) {
        return findOneByEmail(EmailAddress.canonical(email));
    }

    /** Duplicate-account check, case- and whitespace-insensitive. See {@link #findByEmail}. */
    default boolean existsByEmail(String email) {
        return existsOneByEmail(EmailAddress.canonical(email));
    }

    /**
     * Derived exact-match query behind {@link #findByEmail}. Always prefer that method: this one
     * takes the address as literally as the database does, so anything but an already-canonical
     * argument silently finds nothing.
     */
    Optional<User> findOneByEmail(String email);

    /** Derived exact-match query behind {@link #existsByEmail}. Prefer that method. */
    boolean existsOneByEmail(String email);

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
