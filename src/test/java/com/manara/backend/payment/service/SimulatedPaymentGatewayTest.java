package com.manara.backend.payment.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment seam, and the honesty of what it does.
 *
 * <p>No money moves here. What is real is the {@code sim_} prefix, which keeps a simulated grant
 * distinguishable from a paid one for as long as this class is the only implementation — and the
 * fact that no card data reaches this application at all.
 */
class SimulatedPaymentGatewayTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);

    private final SimulatedPaymentGateway gateway = new SimulatedPaymentGateway(
            Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));

    private final PaymentCharge charge =
            new PaymentCharge(BigDecimal.valueOf(490), "Course", "course-7:student-20:purchase");

    @Test
    void anInstrumentIsAcceptedAndReceiptedAsASimulation() {
        var receipt = gateway.charge(charge, instrument());

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

    /**
     * The guard that matters, and the reason the card-shape tests that used to live here are gone.
     *
     * <p>This application has no payment provider, so any card number reaching it would be a real
     * primary account number held by a server with no acquirer and no PCI DSS scope. This asserts
     * the fields cannot come back by accident — a reinstated {@code cardNumber} or {@code cvc}
     * fails the build here rather than quietly resuming collection.
     */
    @Test
    void thePaymentInstrumentCarriesNoCardData() {
        var forbidden = Arrays.stream(PaymentMethodRequest.class.getDeclaredFields())
                .map(Field::getName)
                .map(String::toLowerCase)
                .filter(name -> name.contains("card")
                        || name.contains("cvc")
                        || name.contains("cvv")
                        || name.contains("pan")
                        || name.equals("expiry"))
                .toList();

        assertThat(forbidden)
                .as("PaymentMethodRequest must never carry card data while the gateway is simulated")
                .isEmpty();
    }

    private PaymentMethodRequest instrument() {
        return PaymentMethodRequest.builder().name("Learner").email("learner@example.com").build();
    }
}
