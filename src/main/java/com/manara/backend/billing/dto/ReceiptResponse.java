package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A receipt as issued. {@code fiscal} is always {@code false}: formal tax invoices need an approved
 * business and tax configuration the platform does not have yet.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ReceiptResponse(
        String number,
        LocalDateTime issuedAt,
        boolean simulated,
        boolean fiscal,
        String customerName,
        String customerEmail,
        String lineDescription,
        BigDecimal amount,
        String currency,
        String transactionReference,
        String gatewayReference) {
}
