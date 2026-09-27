package com.manara.backend.billing.dto;

import java.util.List;
import java.util.Map;

/**
 * One page of transactions and a summary of <em>every</em> row the same filters match (not just
 * this page).
 *
 * @param confirmedTotals per currency, LIVE and PAID only — simulated and legacy rows never count
 * @param provenanceCounts how many matching rows each provenance has, so a client can say what the
 *                         totals leave out
 */
public record TransactionPageResponse(
        List<TransactionSummaryResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        List<CurrencyTotalResponse> confirmedTotals,
        Map<String, Long> provenanceCounts) {
}
