package com.manara.backend.billing.model;

/**
 * A transaction's payment status. Refund state lives in {@code refundedAmount} and these values;
 * course access is never read off this — the entitlement is its own record.
 */
public enum TransactionStatus {
    AWAITING_PAYMENT,
    PROCESSING,
    PAID,
    FAILED,
    CANCELLED,
    REFUNDED,
    PARTIALLY_REFUNDED
}
