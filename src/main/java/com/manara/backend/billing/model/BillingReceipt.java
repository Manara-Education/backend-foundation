package com.manara.backend.billing.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * The receipt issued for a paid transaction: a snapshot, written once and never updated. A later
 * rename of the course or the account does not change what the receipt says. No setters.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_receipts")
public class BillingReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 40)
    private String number;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private PaymentTransaction transaction;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(nullable = false, updatable = false)
    private boolean simulated;

    @Column(name = "customer_name", nullable = false, updatable = false)
    private String customerName;

    @Column(name = "customer_email", nullable = false, updatable = false)
    private String customerEmail;

    @Column(name = "line_description", nullable = false, updatable = false)
    private String lineDescription;

    @Column(nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "gateway_reference", updatable = false, length = 100)
    private String gatewayReference;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BillingReceipt other)) return false;
        return number != null && number.equals(other.number);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(number);
    }
}
