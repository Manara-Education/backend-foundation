package com.manara.backend.course.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A recurring access plan offered by a {@link CourseAccessType#SUBSCRIPTION} course.
 *
 * <p>Plans are definitions only — this codebase has no payment provider and no subscription
 * lifecycle. They describe what an instructor offers; charging for them is out of scope.
 *
 * <h2>An offer, and then a receipt</h2>
 * A plan starts as an offer the instructor controls. The moment a learner buys it, the row stops
 * being only an offer and becomes part of their contract: their entitlement and their subscription
 * both point at it, and what they paid and when their term ends were computed from it.
 *
 * <p>So removing a plan from the offer must not remove the row. It used to: the aggregate save
 * hard-deleted every plan the payload no longer mentioned, the foreign keys from
 * {@code course_entitlements} and {@code course_subscriptions} refused, and the instructor was
 * answered with an opaque {@code 409} they could do nothing about — permanently. A course could
 * never leave {@code SUBSCRIPTION} again once anybody had subscribed to it.
 *
 * <p>{@link #retiredAt} is how the two facts live together. A retired plan is off the offer — it
 * cannot be bought, it is not returned to the editor and it is not shown to learners — and its row
 * is still there, unchanged, for every record that was written against it. Editing a plan already
 * behaved this way: renaming or re-pricing one changes the offer, never the term somebody bought.
 * Retirement extends the same rule to removal.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "subscription_plans",
        indexes = @Index(name = "idx_subscription_plans_course_id", columnList = "course_id")
)
public class SubscriptionPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private Integer duration;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SubscriptionUnit unit;

    @Column(nullable = false)
    private BigDecimal price;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    /**
     * When this plan stopped being offered, or {@code null} while it is still on sale.
     *
     * <p>Retirement is one-way and affects only future purchases. Nothing about an existing
     * subscriber changes when their plan is retired: their entitlement stands, their term runs to
     * the end they paid for, and {@code price_paid} on their subscription row is what they were
     * actually charged.
     */
    @Column(name = "retired_at")
    private LocalDateTime retiredAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** Whether this plan is still on offer. Retired plans cannot be bought. */
    public boolean isActive() {
        return retiredAt == null;
    }

    /** Takes the plan off the offer, keeping the row for everything already bought against it. */
    public void retire(LocalDateTime at) {
        if (retiredAt == null) {
            this.retiredAt = at;
        }
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
        if (!(o instanceof SubscriptionPlan other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
