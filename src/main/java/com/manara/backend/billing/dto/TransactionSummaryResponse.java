package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A row of "الفواتير والمدفوعات".
 *
 * @param amount     {@code null} when unknown — never reported as zero
 * @param provenance LIVE, SIMULATED or LEGACY; only LIVE is money received
 * @param courseAccess ACTIVE or NONE — the learner's access now, which is not the payment's status
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionSummaryResponse(
        String reference,
        String purpose,
        BillingCourseResponse course,
        String description,
        BigDecimal amount,
        String currency,
        String status,
        String provenance,
        LocalDateTime createdAt,
        LocalDateTime paidAt,
        String receiptNumber,
        String courseAccess) {
}
