package com.manara.backend.banner.model;

import com.manara.backend.profile.model.Student;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One learner's permanent refusal of one banner.
 *
 * <p>Only {@link BannerDisplayFrequency#ONCE_PER_STUDENT} banners produce these rows. That mode
 * promises a learner sees the banner once — not once per browser — so the refusal cannot live in
 * the browser that made it. The other two modes are scoped to a visit by definition and are kept
 * client-side, where re-showing after a session ends is the intended behaviour rather than a bug.
 *
 * <p>Unique on {@code (banner_id, student_id)}: dismissing twice is the same fact, not two.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "banner_dismissals",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_banner_dismissals_banner_student",
                columnNames = {"banner_id", "student_id"}))
public class BannerDismissal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "banner_id", nullable = false)
    private Banner banner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "dismissed_at", nullable = false, updatable = false)
    private LocalDateTime dismissedAt;

    @PrePersist
    protected void onCreate() {
        if (dismissedAt == null) {
            dismissedAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BannerDismissal other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
