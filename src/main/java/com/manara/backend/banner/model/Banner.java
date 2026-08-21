package com.manara.backend.banner.model;

import com.manara.backend.profile.model.Instructor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A promotional announcement an instructor writes and learners see on their home screen.
 *
 * <p>The banner carries two names on purpose. {@code internalName} is how its owner finds it in a
 * list of a dozen and never leaves the management screen; {@code title} is the one sentence a
 * learner reads. Keeping them apart is what lets an instructor call something "Ramadan 2026 — v3"
 * without a learner ever seeing that.
 *
 * <p>Its window is open at both ends: {@code startAt == null} means "already running" and
 * {@code endAt == null} means "until switched off". {@code timezone} is the zone the owner authored
 * those two in — the instants themselves are absolute, so it is display context for the editor, not
 * an input to the scheduling decision.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "banners",
        indexes = {
                @Index(name = "idx_banners_instructor_id", columnList = "instructor_id"),
                @Index(name = "idx_banners_priority", columnList = "priority")
        })
public class Banner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The owner's own label for this banner. Never sent to a learner. */
    @Column(nullable = false)
    private String internalName;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "image_url", length = 1024)
    private String imageUrl;

    @Column(name = "cta_label")
    private String callToActionLabel;

    @Column(name = "cta_url", length = 1024)
    private String callToActionUrl;

    /** {@code null} means the banner is already running. */
    @Column(name = "start_at")
    private LocalDateTime startAt;

    /** {@code null} means the banner runs until it is switched off. */
    @Column(name = "end_at")
    private LocalDateTime endAt;

    /** The zone the owner authored {@link #startAt} / {@link #endAt} in, for the editor to read back. */
    @Column(nullable = false, length = 64)
    private String timezone;

    /** Lower comes first. Reordering the management list rewrites this across the whole set. */
    @Column(nullable = false)
    private Integer priority;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** Whether a learner may close the banner at all. */
    @Builder.Default
    @Column(nullable = false)
    private boolean dismissible = true;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "display_frequency", nullable = false, length = 32)
    private BannerDisplayFrequency displayFrequency = BannerDisplayFrequency.EVERY_VISIT;

    /** A draft is the owner's unfinished work: never delivered, whatever its window or enabled flag. */
    @Builder.Default
    @Column(nullable = false)
    private boolean draft = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id", nullable = false)
    private Instructor instructor;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Banner other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
