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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
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
 * A learner's standing right to open one course's content.
 *
 * <p>This is the row every protected read is decided against, and it is deliberately the only one:
 * before it existed, "is this person allowed in" was answered by the presence of an
 * {@link Enrollment}, which conflated three different things — that they joined the course, that
 * they paid for it, and that the access they paid for has not run out.
 *
 * <p>One row per learner per course, enforced by a unique constraint, because access is a single
 * current fact rather than a history. Renewing a subscription moves this row's window forward; the
 * history of what was bought lives in {@link CourseSubscription}.
 *
 * <p>{@code expiresAt == null} means perpetual, which is what {@link EntitlementSource#FREE} and
 * {@link EntitlementSource#PURCHASE} always are. Only a subscription carries an end.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "course_entitlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_course_entitlements_course_student",
                columnNames = {"course_id", "student_id"}))
public class CourseEntitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EntitlementSource source;

    /**
     * The plan the current window was bought under. Null for a free grant or an outright purchase,
     * and kept even after the window closes so the renewal screen can offer the same plan back.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscription_plan_id")
    private SubscriptionPlan subscriptionPlan;

    @Column(name = "starts_at", nullable = false)
    private LocalDateTime startsAt;

    /** {@code null} means the access never ends. */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** The whole access rule, in one place. */
    public boolean isActiveAt(LocalDateTime now) {
        return !startsAt.isAfter(now) && (expiresAt == null || expiresAt.isAfter(now));
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
        if (!(o instanceof CourseEntitlement other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
