package com.manara.backend.payment.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.model.PaymentReceipt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A gateway that takes no money.
 *
 * <p><strong>This is a simulation.</strong> No provider is contacted, no card is authorized and no
 * funds move. What it does is real: it applies the card-shape checks the checkout form already
 * enforces on the client, so a malformed instrument is refused server-side too, and it issues a
 * reference that the entitlement and subscription rows store — giving every grant in the database a
 * traceable origin from the day a real provider replaces this class.
 *
 * <p>The {@code sim_} prefix on every reference is the point: no row created by this gateway can
 * ever be mistaken for one backed by an actual charge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final String REFERENCE_PREFIX = "sim_";
    private static final int MIN_CARD_DIGITS = 15;
    private static final int MAX_CARD_DIGITS = 19;

    private final Clock clock;

    @Override
    public PaymentReceipt charge(PaymentCharge charge, PaymentMethodRequest paymentMethod) {
        validate(paymentMethod);

        PaymentReceipt receipt = new PaymentReceipt(
                REFERENCE_PREFIX + UUID.randomUUID(),
                charge.amount(),
                LocalDateTime.now(clock));

        log.info("SIMULATED payment accepted - no money moved. reference={} amount={} idempotencyKey={} description={}",
                receipt.reference(), charge.amount(), charge.idempotencyKey(), charge.description());

        return receipt;
    }

    private void validate(PaymentMethodRequest paymentMethod) {
        if (paymentMethod == null) {
            throw new BusinessException("error.payment.required");
        }
        String digits = paymentMethod.getCardNumber() == null
                ? ""
                : paymentMethod.getCardNumber().replaceAll("\\D", "");
        if (digits.length() < MIN_CARD_DIGITS || digits.length() > MAX_CARD_DIGITS) {
            throw new BusinessException("error.payment.invalidCard");
        }
        String cvc = paymentMethod.getCvc();
        if (cvc == null || cvc.length() < 3 || cvc.length() > 4) {
            throw new BusinessException("error.payment.invalidCvc");
        }
        if (paymentMethod.getExpiry() == null || !paymentMethod.getExpiry().contains("/")) {
            throw new BusinessException("error.payment.invalidExpiry");
        }
    }
}
