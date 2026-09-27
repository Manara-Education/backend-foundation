package com.manara.backend.billing.model;

import com.manara.backend.course.model.Course;
import com.manara.backend.profile.model.Student;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

/**
 * One charge the platform took for a course, with where its facts came from. See V20.
 *
 * <p>The purchase or subscription row it paid for is linked by id rather than mapped, so reading a
 * transaction never drags the entitlement graph along, and the two tables stay owned by their
 * feature.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The public identifier. Row ids are never exposed. */
    @Column(nullable = false, updatable = false)
    private UUID reference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionPurpose purpose;

    @Column(name = "purchase_id", updatable = false)
    private Long purchaseId;

    @Column(name = "subscription_id", updatable = false)
    private Long subscriptionId;

    @Column(name = "subscription_plan_id", updatable = false)
    private Long subscriptionPlanId;

    /** What was bought, as it was named when it was bought. */
    @Column(name = "line_description", nullable = false, updatable = false)
    private String lineDescription;

    /** {@code null} is unknown — never zero. */
    @Column(precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TransactionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransactionProvenance provenance;

    @Column(name = "gateway_reference", length = 100, updatable = false)
    private String gatewayReference;

    @Builder.Default
    @Column(name = "refunded_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal refundedAmount = BigDecimal.ZERO;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    /** Where the row came from: {@code checkout:<reference>}, or a backfill key from V20. */
    @Column(name = "source_key", nullable = false, updatable = false, length = 80)
    private String sourceKey;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PaymentTransaction other)) return false;
        return reference != null && reference.equals(other.reference);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reference);
    }
}
