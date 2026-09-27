package com.manara.backend.billing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


/**
 * Whether students may submit refund requests, and within what window.
 *
 * <p>{@code enabled} is off by default because no one can review a request yet: there is no staff
 * review surface, and a request that nobody answers would be a promise the platform cannot keep.
 * Turn it on only together with a review process.
 *
 * @param windowDays calendar days after the purchase date within which a request is accepted,
 *                   inclusive: 14, as the published Terms (1.0, section 6) state — "within 14 days of
 *                   the purchase date". Counted in days, not hours, so it is never stricter than that.
 */
@ConfigurationProperties(prefix = "app.refund-requests")
public record RefundRequestProperties(boolean enabled, int windowDays) {

    public RefundRequestProperties {
        windowDays = windowDays <= 0 ? 14 : windowDays;
    }
}
