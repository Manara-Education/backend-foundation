package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One transaction in full. Money fields are {@code null} when the amount is unknown. {@code discount}
 * is zero only because no discount exists in the product today; it is not a claim about the past.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record TransactionDetailResponse(
        TransactionSummaryResponse summary,
        List<LineResponse> lines,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal total,
        BigDecimal refundedAmount,
        String gatewayReference,
        SubscriptionTermResponse subscriptionTerm) {

    public record LineResponse(String description, BigDecimal amount) {
    }

    public record SubscriptionTermResponse(Long subscriptionId, LocalDateTime startsAt, LocalDateTime expiresAt) {
    }
}
