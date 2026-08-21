package com.manara.backend.payment.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment seam, and the honesty of what it does.
 *
 * <p>No money moves here. What is real is the refusal of a malformed instrument — the checkout form
 * already checks the same things client-side, and a request that skips the form must not skip them —
 * and the {@code sim_} prefix, which is what keeps a simulated grant distinguishable from a paid one
 * for as long as this class is the only implementation.
 */
class SimulatedPaymentGatewayTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));

    private final PaymentCharge charge =
            new PaymentCharge(BigDecimal.valueOf(490), "Course", "course-7:student-20:purchase");

    @Test
    void aWellFormedCardIsAcceptedAndReceiptedAsASimulation() {
        var receipt = gateway.charge(charge, card("4242 4242 4242 4242", "12 / 30", "123"));

        assertThat(receipt.reference()).startsWith("sim_");
        assertThat(receipt.amount()).isEqualByComparingTo("490");
        assertThat(receipt.paidAt()).isEqualTo(NOW);
    }

    @Test
    void aMissingInstrumentIsRefused() {
        assertThatThrownBy(() -> gateway.charge(charge, null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.payment.required");
    }

    @Test
    void aShortCardNumberIsRefused() {
        assertThatThrownBy(() -> gateway.charge(charge, card("4242", "12 / 30", "123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.payment.invalidCard");
    }

    @Test
    void aMalformedCvcIsRefused() {
        assertThatThrownBy(() -> gateway.charge(charge, card("4242424242424242", "12 / 30", "1")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.payment.invalidCvc");
    }

    @Test
    void aMalformedExpiryIsRefused() {
        assertThatThrownBy(() -> gateway.charge(charge, card("4242424242424242", "1230", "123")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.payment.invalidExpiry");
    }

    private PaymentMethodRequest card(String number, String expiry, String cvc) {
        return PaymentMethodRequest.builder()
                .cardNumber(number).expiry(expiry).cvc(cvc).name("Learner").build();
    }
}
