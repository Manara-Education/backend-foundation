package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What this deployment can actually do with money, for the client to show only what works.
 *
 * <p>Derived from configuration and the payment adapters present — never a promise. Every value is
 * also enforced by the server itself; a client that ignores this answer is refused anyway.
 *
 * @param commerceMode    FREE_ONLY, DEMONSTRATION or LIVE
 * @param provider        the real payment provider in use, or {@code null} when there is none
 * @param oneTimeCheckout whether paid checkout is accepted at all (simulated in DEMONSTRATION)
 * @param simulated       whether an accepted checkout moves no money
 * @param methodTypes     payment method types a learner can choose; empty with no provider
 * @param refunds         whether a provider can return money for a transaction
 * @param refundRequests  whether a student may submit a refund request for review; a request is not
 *                        a refund, and this can be true while {@code refunds} is false
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BillingCapabilitiesResponse(
        String commerceMode,
        String provider,
        boolean oneTimeCheckout,
        boolean simulated,
        List<String> methodTypes,
        boolean savedMethods,
        boolean recurringCharges,
        boolean statusRefresh,
        boolean refunds,
        boolean refundRequests) {
}
