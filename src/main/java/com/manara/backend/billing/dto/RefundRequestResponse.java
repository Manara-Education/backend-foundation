package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A refund request as its owner sees it. {@code status} is the review state only; whether money was
 * returned is the transaction's {@code refundedAmount}.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RefundRequestResponse(
        String reference,
        String transactionReference,
        String reason,
        String note,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime createdAt,
        LocalDateTime decidedAt,
        String decisionNote) {
}
