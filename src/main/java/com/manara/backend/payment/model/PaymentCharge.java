package com.manara.backend.payment.model;

import java.math.BigDecimal;

/**
 * One amount to be taken, described by the domain that asked for it.
 *
 * @param amount         what to charge — always computed by the server from its own records, never
 *                       read off the request
 * @param description    what the learner is paying for, for the receipt
 * @param idempotencyKey stable for a given (learner, course, purpose), so a retried checkout is
 *                       recognisable as the same charge rather than a second one
 */
public record PaymentCharge(BigDecimal amount, String description, String idempotencyKey) {
}
