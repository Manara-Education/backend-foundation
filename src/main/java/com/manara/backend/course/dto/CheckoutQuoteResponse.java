package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manara.backend.course.model.CourseAccessType;

import java.math.BigDecimal;

/**
 * What checking out would cost, computed the way checkout charges — from the course's and the
 * plan's stored prices. A quote authorises nothing: checkout re-derives the price itself.
 *
 * @param accessKind    PERPETUAL for a purchase or a free course, FIXED_TERM for a subscription plan
 * @param renewalMode   FIXED for a subscription (it ends and nothing renews it); {@code null} otherwise
 * @param payable       whether a checkout now would be accepted; see {@code unavailableReason}
 * @param unavailableReason PAYMENTS_UNAVAILABLE or ALREADY_ENTITLED, or {@code null}
 * @param simulated     {@code true} when this deployment only simulates payments, so the client can
 *                      say so before the learner confirms
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CheckoutQuoteResponse(
        Long courseId,
        Long planId,
        CourseAccessType accessType,
        BigDecimal subtotal,
        BigDecimal discount,
        BigDecimal amount,
        String currency,
        String accessKind,
        Integer accessDuration,
        String accessUnit,
        String renewalMode,
        boolean payable,
        String unavailableReason,
        boolean simulated) {
}
