package com.manara.backend.billing.service;

import com.manara.backend.billing.config.RefundRequestProperties;
import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.RefundEligibility;
import com.manara.backend.billing.model.RefundRequestStatus;
import com.manara.backend.billing.model.TransactionProvenance;
import com.manara.backend.billing.model.TransactionStatus;
import com.manara.backend.billing.repository.RefundRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a transaction can be the subject of a new refund request. Shown with the transaction and
 * checked again on submission.
 *
 * <p>Only LIVE transactions qualify — a simulated or legacy row records no money this platform can
 * verify it received — and only within the policy window: through the 14th calendar day after the
 * purchase date, in server time. The request's own date is what counts (Terms 1.0, section 7).
 */
@Component
@RequiredArgsConstructor
public class RefundPolicy {

    static final Set<RefundRequestStatus> OPEN = EnumSet.of(RefundRequestStatus.SUBMITTED, RefundRequestStatus.APPROVED);

    private final RefundRequestProperties properties;
    private final RefundRequestRepository refundRequestRepository;
    private final Clock clock;

    public boolean acceptingRequests() {
        return properties.enabled();
    }

    public RefundEligibility eligibility(PaymentTransaction t) {
        if (!properties.enabled()) return RefundEligibility.UNAVAILABLE;
        if (t.getProvenance() != TransactionProvenance.LIVE) return RefundEligibility.NOT_LIVE;
        if (t.getStatus() != TransactionStatus.PAID) return RefundEligibility.NOT_PAID;
        if (refundable(t).signum() <= 0 || t.getCurrency() == null) return RefundEligibility.NO_REFUNDABLE_AMOUNT;
        if (t.getPaidAt() == null
                || LocalDate.now(clock).isAfter(t.getPaidAt().toLocalDate().plusDays(properties.windowDays()))) {
            return RefundEligibility.WINDOW_CLOSED;
        }
        if (refundRequestRepository.existsByTransactionIdAndStatusIn(t.getId(), OPEN)) return RefundEligibility.REQUEST_OPEN;
        return RefundEligibility.ELIGIBLE;
    }

    /** What remains to refund: the recorded amount less what was already refunded; zero when unknown. */
    public static BigDecimal refundable(PaymentTransaction t) {
        return t.getAmount() == null ? BigDecimal.ZERO : t.getAmount().subtract(t.getRefundedAmount());
    }
}
