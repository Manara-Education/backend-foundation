package com.manara.backend.billing.model;

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

/** A student's request to be refunded for one transaction. See V21 for what it does and does not change. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID reference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private PaymentTransaction transaction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private Student student;

    @Enumerated(EnumType.STRING)
    /** Optional: the Terms grant the refund without a reason. */
    @Column(length = 30, updatable = false)
    private RefundReason reason;

    @Column(length = 1000, updatable = false)
    private String note;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundRequestStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "decision_note", length = 1000)
    private String decisionNote;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefundRequest other)) return false;
        return reference != null && reference.equals(other.reference);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(reference);
    }
}
