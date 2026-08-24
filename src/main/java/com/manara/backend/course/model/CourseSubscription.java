package com.manara.backend.course.model;

import com.manara.backend.profile.model.Student;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One purchased subscription term.
 *
 * <p>A term is never rewritten: renewing writes a new row and closes the previous one, so the
 * sequence of rows is the record of what a learner actually bought and when. The current access
 * window is mirrored onto {@link CourseEntitlement} so that answering "may they open this lesson"
 * stays one indexed read rather than a scan of this table.
 *
 * <p>{@code pricePaid} is stored rather than read back from the plan: an instructor may reprice a
 * plan afterwards, and that must not rewrite what someone was charged. {@code paymentReference}
 * comes from the payment gateway — today a simulated one, whose references are prefixed
 * {@code sim_}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "course_subscriptions",
        indexes = {
                @Index(name = "idx_course_subscriptions_student_course", columnList = "student_id, course_id"),
                @Index(name = "idx_course_subscriptions_course_id", columnList = "course_id")
        })
public class CourseSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /**
     * The plan bought. Kept as a reference so the renewal screen can pre-select it; the figures that
     * mattered at purchase time are copied onto this row rather than read back through it.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_plan_id", nullable = false)
    private SubscriptionPlan plan;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** Computed by the server from the plan's duration and unit. Never supplied by a client. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionStatus status;

    /** What was actually charged, at the price the plan carried on the day. */
    @Column(name = "price_paid", nullable = false)
    private BigDecimal pricePaid;

    @Column(name = "payment_reference", length = 100)
    private String paymentReference;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public boolean isActiveAt(LocalDateTime now) {
        return status == SubscriptionStatus.ACTIVE && expiresAt.isAfter(now);
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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CourseSubscription other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
