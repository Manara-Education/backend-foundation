package com.manara.backend.payment.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Proof that a charge was accepted.
 *
 * @param reference the gateway's own identifier for the charge, stored alongside whatever the
 *                  payment bought so the two can be reconciled later
 * @param simulated {@code true} when no money moved because the gateway is the simulator. Checkout
 *                  grants nothing from such a receipt outside a demonstration deployment. A required
 *                  component rather than something inferred from a reference prefix, so no gateway
 *                  can issue a receipt without saying which kind it is
 */
public record PaymentReceipt(String reference, BigDecimal amount, LocalDateTime paidAt, boolean simulated) {
}
