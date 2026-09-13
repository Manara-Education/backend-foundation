package com.manara.backend.user.repository;

import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Everything about an account that a request has to re-check, in one row read.
     *
     * <p>The principal the session hands over is a snapshot serialised at sign-in. It goes stale in
     * every direction that matters: it still says "reset required" after the password was changed,
     * still says "no reset required" for a session opened before an operator flagged the account,
     * and still names the role the account held at sign-in after that role has been taken away. The
     * database is the only copy of any of this that anything is allowed to believe.
     *
     * <p>Three fields rather than three queries, and a projection rather than the entity. The
     * request pipeline already paid for one {@code users} read per authenticated request to check
     * the reset flag alone; widening that one read to carry the role and the epoch as well costs no
     * extra round trip. The projection also keeps the result out of the persistence context — a
     * managed entity handed to a filter would be a row that anything downstream could dirty into an
     * unintended UPDATE.
     */
    interface AuthState {

        Long getId();

        Role getRole();

        boolean isRequiresPasswordReset();

        long getAuthVersion();
    }

    /**
     * The single {@code users} read every authenticated request makes. See {@link AuthState}.
     *
     * <p>Written out rather than derived so the column list is visible here and stays narrow: this
     * runs on every authenticated request, and a projection that quietly widened into a full entity
     * load would not announce itself.
     */
    @Query("""
            select u.id as id,
                   u.role as role,
                   u.requiresPasswordReset as requiresPasswordReset,
                   u.authVersion as authVersion
              from User u
             where u.id = :id
            """)
    Optional<AuthState> findAuthStateById(@Param("id") Long id);

    /**
     * Moves the account to a new authentication epoch, retiring every session opened under the old
     * one.
     *
     * <p>An increment computed by the database rather than a value read, changed and written back:
     * two credential changes racing must produce two increments, and a read-modify-write would let
     * one of them overwrite the other and quietly leave a revoked session valid.
     *
     * <p>{@code flushAutomatically} so a password hash still pending in the persistence context is
     * written before this runs — the two must land in the same transaction, in that order, or a
     * rollback could separate the new credential from the epoch that retires the old sessions.
     * {@code clearAutomatically} because this statement goes straight to the database and the
     * persistence context would otherwise keep serving the pre-bump number to anything that re-read
     * the row inside the same transaction, which is exactly what the caller does next.
     *
     * @return the number of rows affected: 1 for an account that exists, 0 for one that does not
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update User u set u.authVersion = u.authVersion + 1 where u.id = :id")
    int bumpAuthVersion(@Param("id") Long id);
}
