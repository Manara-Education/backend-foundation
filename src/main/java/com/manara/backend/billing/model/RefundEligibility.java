package com.manara.backend.billing.model;

/** Whether a transaction can be the subject of a new refund request, and if not, the first reason why. */
public enum RefundEligibility {
    ELIGIBLE,
    /** Requests are not accepted by this deployment. */
    UNAVAILABLE,
    /** Simulated or legacy: no money this platform can verify was received. */
    NOT_LIVE,
    /** Not in the PAID state: pending, failed, cancelled or already refunded. */
    NOT_PAID,
    /** The amount or currency is unknown, or nothing remains to refund. */
    NO_REFUNDABLE_AMOUNT,
    /** The policy window after payment has passed. */
    WINDOW_CLOSED,
    /** A request for this transaction is already open. */
    REQUEST_OPEN
}
