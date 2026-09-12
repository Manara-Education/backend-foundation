package com.manara.backend.payment.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.model.PaymentReceipt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The gateway of a deployment that takes no payments: every charge is refused.
 *
 * <p>Registered only in FREE_ONLY, so that checkout, which needs a gateway to exist, can start with
 * neither a provider nor the simulator. Checkout refuses a paid course in FREE_ONLY before charging,
 * so this is not asked in practice. It refuses anyway, so a path that did reach it would still grant
 * nothing — and CommerceConfig never counts it as a provider in LIVE.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "manara.commerce.mode", havingValue = "FREE_ONLY")
public class PaymentsUnavailableGateway implements PaymentGateway {

    @Override
    public PaymentReceipt charge(PaymentCharge charge, PaymentMethodRequest paymentMethod) {
        log.warn("Payment REFUSED - this deployment takes no payments (FREE_ONLY). idempotencyKey={}",
                charge.idempotencyKey());
        throw new BusinessException(ErrorCode.PAYMENTS_UNAVAILABLE, "error.payment.unavailable");
    }
}
