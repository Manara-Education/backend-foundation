package com.manara.backend.course.model;

import com.manara.backend.profile.model.Student;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
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
 * One outright purchase of a course.
 *
 * <p>The one-off twin of {@link CourseSubscription}, and it exists for the same reason that one
 * carries {@code pricePaid}: an instructor may reprice a course afterwards, and that must not
 * rewrite what somebody was charged. Until this row existed the subscription path had that record
 * and the purchase path did not — {@code CheckoutProcessor} charged
 * {@link Course#getPurchasePrice()}, handed the receipt to the HTTP response and persisted nothing,
 * so the only surviving number was the course's <em>current</em> price. A course repriced from 500
 * to 700 left no way to say what its existing buyers had paid.
 *
 * <p>Written once, inside the checkout transaction, and never updated. Every column is
 * {@code updatable = false}: this is what happened, not a projection of what the course costs today.
 *
 * <h2>Not the access rule</h2>
 * This row does not grant anything. Whether a learner may open the course is
 * {@link CourseEntitlement}'s answer and nothing here is consulted for it — which is precisely why
 * a price change cannot close a course somebody already bought. Membership is
 * {@link Enrollment}'s; this is the receipt.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "course_purchases",
        indexes = {
                @Index(name = "idx_course_purchases_student_course", columnList = "student_id, course_id"),
                @Index(name = "idx_course_purchases_course_id", columnList = "course_id")
        })
public class CoursePurchase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false, updatable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false, updatable = false)
    private Student student;

    /** What the course was listed at when it was bought. */
    @Column(name = "list_price", nullable = false, updatable = false)
    private BigDecimal listPrice;

    /**
     * What was actually taken.
     *
     * <p>Kept separately from {@link #listPrice} even though the two are equal today. There is no
     * discount or promotion in the product yet; when there is, the difference between what a course
     * was advertised at and what a learner paid is exactly the fact an audit needs, and a schema
     * that stored one number would have thrown it away. Reconciliation reads this column.
     */
    @Column(name = "amount_paid", nullable = false, updatable = false)
    private BigDecimal amountPaid;

    /**
     * ISO-4217, denormalised onto the row rather than assumed.
     *
     * <p>The platform prices in EGP throughout and nothing configures otherwise, so this is
     * constant today. It is stored anyway because a historical amount without its currency is not a
     * record of anything — and the day a second currency appears, every row written before it would
     * otherwise have to be guessed at.
     */
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    /** The gateway's own identifier for the charge, for reconciliation. */
    @Column(name = "payment_reference", updatable = false, length = 100)
    private String paymentReference;

    @Column(name = "purchased_at", nullable = false, updatable = false)
    private LocalDateTime purchasedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CoursePurchase other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
