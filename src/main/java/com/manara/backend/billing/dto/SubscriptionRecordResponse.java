package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One subscription the learner bought. Every subscription today is a fixed term: it ends at
 * {@code expiresAt} and nothing renews it, which is what {@code renewalMode: FIXED} states.
 *
 * @param displayStatus FIXED_ACCESS while the term runs, EXPIRED after it
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record SubscriptionRecordResponse(
        Long id,
        BillingCourseResponse course,
        PlanResponse plan,
        BigDecimal pricePaid,
        String currency,
        LocalDateTime startsAt,
        LocalDateTime expiresAt,
        String status,
        String displayStatus,
        String renewalMode,
        String courseAccess,
        String transactionReference,
        String provenance) {

    public record PlanResponse(Long id, String name, Integer duration, String unit) {
    }
}
