package com.manara.backend.billing;

import com.manara.backend.billing.service.BillingCapabilities;
import com.manara.backend.billing.service.RefundPolicy;
import com.manara.backend.payment.config.CommerceMode;
import com.manara.backend.payment.model.PaymentReceipt;
import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.payment.service.PaymentsUnavailableGateway;
import com.manara.backend.payment.service.SimulatedPaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The capability answer follows configuration and the adapter wired in — nothing else. */
class BillingCapabilitiesTest {

    private static final RefundPolicy CLOSED = mock(RefundPolicy.class);

    @Test
    @DisplayName("FREE_ONLY: no paid checkout and no provider capability")
    void freeOnly() {
        var answer = new BillingCapabilities(CommerceMode.FREE_ONLY, mock(PaymentsUnavailableGateway.class), CLOSED).current();
        assertThat(answer.oneTimeCheckout()).isFalse();
        assertThat(answer.simulated()).isFalse();
        assertThat(answer.provider()).isNull();
        assertThat(answer.methodTypes()).isEmpty();
        assertThat(answer.savedMethods() || answer.recurringCharges() || answer.statusRefresh() || answer.refunds()).isFalse();
        assertThat(answer.refundRequests()).isFalse();
    }

    @Test
    @DisplayName("Refund requests follow the policy switch, and never imply a provider refund")
    void refundRequestsFollowTheSwitch() {
        RefundPolicy open = mock(RefundPolicy.class);
        when(open.acceptingRequests()).thenReturn(true);
        var answer = new BillingCapabilities(CommerceMode.DEMONSTRATION, mock(SimulatedPaymentGateway.class), open).current();
        assertThat(answer.refundRequests()).isTrue();
        assertThat(answer.refunds()).isFalse();
    }

    @Test
    @DisplayName("DEMONSTRATION: checkout is accepted and says it is simulated; nothing provider-backed")
    void demonstration() {
        var answer = new BillingCapabilities(CommerceMode.DEMONSTRATION, mock(SimulatedPaymentGateway.class), CLOSED).current();
        assertThat(answer.oneTimeCheckout()).isTrue();
        assertThat(answer.simulated()).isTrue();
        assertThat(answer.savedMethods() || answer.recurringCharges() || answer.refunds()).isFalse();
    }

    @Test
    @DisplayName("LIVE with a real adapter: checkout only; saved methods, renewals and refunds stay off until built")
    void liveWithAProvider() {
        PaymentGateway provider = (charge, method) -> new PaymentReceipt("prov_1", charge.amount(), null, false);
        var answer = new BillingCapabilities(CommerceMode.LIVE, provider, CLOSED).current();
        assertThat(answer.oneTimeCheckout()).isTrue();
        assertThat(answer.simulated()).isFalse();
        assertThat(answer.savedMethods() || answer.recurringCharges() || answer.statusRefresh() || answer.refunds()).isFalse();
    }
}
