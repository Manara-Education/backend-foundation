package com.manara.backend.billing.model;

/**
 * Why the student asks for a refund, when they choose to say. Optional: the published Terms grant the
 * refund within the window without a reason. A fixed list, so it can be acted on and reported.
 */
public enum RefundReason {
    ACCESS_PROBLEM,
    NOT_AS_DESCRIBED,
    DUPLICATE_CHARGE,
    CHANGED_MIND,
    OTHER
}
