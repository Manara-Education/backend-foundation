package com.manara.backend.billing.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Everything a receipt document shows, taken from the immutable receipt snapshot — never re-read
 * from the course or the profile, which may have changed since the payment.
 */
public record ReceiptDocument(
        String number,
        LocalDateTime issuedAt,
        boolean simulated,
        String customerName,
        String customerEmail,
        String lineDescription,
        BigDecimal amount,
        String currency,
        String transactionReference,
        String gatewayReference) {
}
