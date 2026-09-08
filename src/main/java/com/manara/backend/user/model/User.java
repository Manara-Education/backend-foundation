package com.manara.backend.user.model;

import com.manara.backend.common.util.EmailAddress;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User implements UserDetails {

    /**
     * Pinned, and it has to stay pinned from here on.
     *
     * <p>This class is the session principal, and the session store serialises it with plain JDK
     * serialisation. Until this constant existed the id was whatever the compiler derived from the
     * field list, so <em>adding a field</em> — which the line below does — silently changed the
     * identity of every {@code User} already sitting in Redis. Those sessions would then fail to
     * deserialise inside the session layer, before any filter of ours runs, and the user would meet
     * a 500 instead of being asked to sign in again.
     *
     * <p>The value is the one the compiler derived for this class immediately before
     * {@code authVersion} was added ({@code serialver} at the commit this change was branched from).
     * Keeping it means a stored principal from the previous release still reads back: the field it
     * does not carry simply comes out as 0, and the request is refused cleanly because the session
     * carries no {@code MANARA_AUTH_VERSION} stamp — a 401, which is the intended migration path.
     *
     * <p>Changing this number, or letting it be recomputed by removing the constant, invalidates
     * every live session in a way that is not graceful. Fields may be added; this must not move.
     */
    private static final long serialVersionUID = -7395413370153448912L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Builder.Default
    @Column(nullable = false)
    private boolean emailVerified = false;

    /**
     * Whether this account must pick a new password before it may use the application.
     *
     * Set out of band -- by an operator handing over a provisioned account, or by a support
     * reset -- and cleared only by {@code AuthService#changePassword}, in the same transaction
     * that persists the new hash. Sign-in still succeeds while it is {@code true}; what the
     * flag withholds is everything after it.
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean requiresPasswordReset = false;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role = Role.STUDENT;

    /**
     * How many times this account's credentials have changed. The epoch a session belongs to.
     *
     * <p>A session records the value it was established under, and every authenticated request
     * compares its record against this column. Changing or resetting the password increments it in
     * the same transaction that writes the new hash, so at the instant the new password becomes
     * real every session opened under the old one stops matching and is refused.
     *
     * <p>Deliberately <em>not</em> {@code @Version}. That annotation means optimistic locking: it
     * would make Hibernate manage the number, bump it on any update to the row, and start throwing
     * on concurrent saves — three behaviours this is not asking for. This counter moves only when a
     * credential changes, and only because {@code UserRepository#bumpAuthVersion} says so.
     *
     * <p>Reading it off this field is never how a request is checked. The principal is a snapshot
     * like any other field here, and a snapshot cannot be its own freshness proof; the value that
     * decides is the one read from the row on each request.
     */
    @Builder.Default
    @Column(nullable = false)
    private long authVersion = 0L;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /**
     * Stores the canonical form of the address, never the raw one.
     *
     * <p>Written out by hand so Lombok's {@code @Setter} does not generate a plain assignment.
     * Every mutation path through the entity therefore keeps the column canonical; the builder,
     * which bypasses setters, is covered by {@code AuthMapper#toUser}, and the database has the
     * final word through {@code ck_users_email_canonical}.
     */
    public void setEmail(String email) {
        this.email = EmailAddress.canonical(email);
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    @NonNull
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Identity is the email address, compared the way the database compares it.
     *
     * <p>Canonicalising both sides rather than calling {@code email.equals(other.email)} keeps
     * this consistent with {@code uk_users_email_lower}: two instances the database would refuse
     * to store side by side must not look like different accounts here either. In practice every
     * persisted row is already canonical, so this only matters for instances built in memory —
     * which is exactly where a raw address can still turn up.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof User other)) return false;
        String canonical = EmailAddress.canonical(email);
        return canonical != null && canonical.equals(EmailAddress.canonical(other.email));
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(EmailAddress.canonical(email));
    }
}
