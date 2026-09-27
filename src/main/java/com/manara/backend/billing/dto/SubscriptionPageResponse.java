package com.manara.backend.billing.dto;

import java.util.List;

/** One page of subscriptions, and counts over all of the learner's subscriptions. */
public record SubscriptionPageResponse(
        List<SubscriptionRecordResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        long activeCount) {
}
