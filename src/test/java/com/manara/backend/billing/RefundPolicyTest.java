package com.manara.backend.billing;

import com.manara.backend.billing.config.RefundRequestProperties;
import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.RefundEligibility;
import com.manara.backend.billing.model.TransactionProvenance;
import com.manara.backend.billing.model.TransactionStatus;
import com.manara.backend.billing.repository.RefundRequestRepository;
import com.manara.backend.billing.service.RefundPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who may ask for a refund, checked in the order the student is told the reason. */
class RefundPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final RefundRequestRepository requests = mock(RefundRequestRepository.class);

    private RefundPolicy policy(boolean enabled) {
        return new RefundPolicy(new RefundRequestProperties(enabled, 0), requests, CLOCK);
    }

    private static PaymentTransaction live(LocalDateTime paidAt) {
        return PaymentTransaction.builder().id(1L).provenance(TransactionProvenance.LIVE).status(TransactionStatus.PAID)
                .amount(new BigDecimal("450.00")).currency("EGP").paidAt(paidAt).build();
    }

    @Test
    @DisplayName("Off by default: nothing is eligible, whatever the payment")
    void offMeansUnavailable() {
        assertThat(new RefundRequestProperties(false, 0).windowDays()).isEqualTo(14);
        assertThat(policy(false).eligibility(live(NOW))).isEqualTo(RefundEligibility.UNAVAILABLE);
    }

    @Test
    @DisplayName("A live, paid, recent payment with no open request is eligible")
    void eligible() {
        assertThat(policy(true).eligibility(live(NOW.minusDays(3)))).isEqualTo(RefundEligibility.ELIGIBLE);
    }

    @Test
    @DisplayName("Simulated and legacy payments are never refundable online")
    void notLive() {
        PaymentTransaction simulated = live(NOW);
        simulated.setProvenance(TransactionProvenance.SIMULATED);
        PaymentTransaction legacy = live(NOW);
        legacy.setProvenance(TransactionProvenance.LEGACY);
        assertThat(policy(true).eligibility(simulated)).isEqualTo(RefundEligibility.NOT_LIVE);
        assertThat(policy(true).eligibility(legacy)).isEqualTo(RefundEligibility.NOT_LIVE);
    }

    @Test
    @DisplayName("Pending or already refunded payments are not paid; unknown amounts have nothing to refund")
    void notPaidOrUnknown() {
        PaymentTransaction pending = live(null);
        pending.setStatus(TransactionStatus.PROCESSING);
        PaymentTransaction unknown = live(NOW);
        unknown.setAmount(null);
        PaymentTransaction spent = live(NOW);
        spent.setRefundedAmount(new BigDecimal("450.00"));
        assertThat(policy(true).eligibility(pending)).isEqualTo(RefundEligibility.NOT_PAID);
        assertThat(policy(true).eligibility(unknown)).isEqualTo(RefundEligibility.NO_REFUNDABLE_AMOUNT);
        assertThat(policy(true).eligibility(spent)).isEqualTo(RefundEligibility.NO_REFUNDABLE_AMOUNT);
    }

    @Test
    @DisplayName("The window runs through the 14th calendar day after the purchase date, whatever the hour")
    void window() {
        LocalDateTime lateOnPurchaseDay = NOW.toLocalDate().minusDays(14).atTime(23, 59);
        LocalDateTime earlyOnPurchaseDay = NOW.toLocalDate().minusDays(14).atTime(0, 1);
        assertThat(policy(true).eligibility(live(earlyOnPurchaseDay))).isEqualTo(RefundEligibility.ELIGIBLE);
        assertThat(policy(true).eligibility(live(lateOnPurchaseDay))).isEqualTo(RefundEligibility.ELIGIBLE);
        assertThat(policy(true).eligibility(live(NOW.minusDays(15)))).isEqualTo(RefundEligibility.WINDOW_CLOSED);
    }

    @Test
    @DisplayName("One open request at a time")
    void requestOpen() {
        when(requests.existsByTransactionIdAndStatusIn(anyLong(), any())).thenReturn(true);
        assertThat(policy(true).eligibility(live(NOW))).isEqualTo(RefundEligibility.REQUEST_OPEN);
    }

}
