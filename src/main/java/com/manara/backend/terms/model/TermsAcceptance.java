package com.manara.backend.terms.model;

import com.manara.backend.user.model.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Objects;

/**
 * The record that an account accepted a specific version of the Terms and Conditions.
 *
 * <p>Written once, inside the registration transaction, and never updated: this is a statement
 * about a moment, not a projection of anything current. Every column is {@code updatable = false}
 * for that reason.
 *
 * <h2>Absence means "unknown", never "declined"</h2>
 * Accounts that existed before this table did have no row, and none was invented for them — a
 * fabricated acceptance is a claim about a person that nobody can support. Code that reads this
 * table must treat a missing row as "we do not know", not as a refusal.
 *
 * <h2>The reference points one way, deliberately</h2>
 * This entity names its {@link User}; {@code User} has no collection of acceptances and must not
 * gain one. That asymmetry is structural, not stylistic: {@code ProfileService#updateProfile} saves
 * the user aggregate on an ordinary profile edit, and a mapped collection here would put consent
 * inside the reach of that save — a cascade or an orphan-removal away from a profile update
 * rewriting or deleting what somebody agreed to. With no mapping, that is not a rule anyone has to
 * remember; it is simply not expressible.
 *
 * <h2>Why {@code Instant} rather than {@code LocalDateTime}</h2>
 * Every other timestamp in this schema is {@code timestamp without time zone}, which is only
 * unambiguous while every writer happens to share one zone. Consent is the one fact here that may
 * have to be produced as evidence years later, so it is stored as {@code timestamptz} and held as
 * an {@link Instant}: a point on the timeline, not a wall-clock reading whose meaning depends on
 * where the JVM was configured. The value is always server-generated from the injected
 * {@code Clock}, never taken from the request.
 *
 * <p>No IP address and no user-agent are collected. Neither is needed to establish that consent was
 * given, and both would turn this into a table of personal data with a retention problem.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "terms_acceptances",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_terms_acceptances_user_version",
                columnNames = {"user_id", "terms_version"}),
        indexes = @Index(name = "idx_terms_acceptances_user_id", columnList = "user_id"))
public class TermsAcceptance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    /**
     * The opaque {@code TermsVersion#id} that was accepted — the exact text, not "the latest".
     * Stored rather than derived so a later publication cannot retroactively change what this
     * account agreed to.
     */
    @Column(name = "terms_version", nullable = false, updatable = false, length = 32)
    private String termsVersion;

    /** Server-generated UTC. Never read from the request; see the class comment. */
    @Column(name = "accepted_at", nullable = false, updatable = false)
    private Instant acceptedAt;

    /**
     * Identity is the pair the unique constraint names: one acceptance of one version by one
     * account. Written out rather than left to Lombok, because {@code @Data}'s generated
     * equals/hashCode over every field — the generated id included — breaks for an
     * IDENTITY-generated entity that has not been flushed yet.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TermsAcceptance other)) return false;
        return user != null && other.user != null
                && Objects.equals(user.getId(), other.user.getId())
                && Objects.equals(termsVersion, other.termsVersion);
    }

    @Override
    public int hashCode() {
        return Objects.hash(user == null ? null : user.getId(), termsVersion);
    }
}
