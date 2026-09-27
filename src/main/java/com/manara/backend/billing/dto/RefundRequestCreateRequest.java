package com.manara.backend.billing.dto;

import com.manara.backend.billing.model.RefundReason;
import jakarta.validation.constraints.Size;

/**
 * A student's refund request. No amount: the server refunds what remains on the transaction. The reason
 * is optional, because the Terms grant the refund without one.
 */
public record RefundRequestCreateRequest(
        RefundReason reason,
        @Size(max = 1000) String note) {
}
