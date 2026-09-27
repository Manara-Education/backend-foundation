package com.manara.backend.billing.model;

/**
 * Review state of a refund request — not whether money was returned. APPROVED means a reviewer
 * accepted it; the refund itself is a provider operation recorded on the transaction.
 */
public enum RefundRequestStatus {
    SUBMITTED,
    APPROVED,
    REJECTED
}
