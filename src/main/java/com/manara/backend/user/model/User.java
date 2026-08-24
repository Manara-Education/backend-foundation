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
