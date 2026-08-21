package com.manara.backend.payment.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Proof that a charge was accepted.
 *
 * @param reference the gateway's own identifier for the charge, stored alongside whatever the
 *                  payment bought so the two can be reconciled later
 */
public record PaymentReceipt(String reference, BigDecimal amount, LocalDateTime paidAt) {
}
