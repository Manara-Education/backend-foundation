package com.manara.backend.payment.service;

import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.model.PaymentReceipt;

/**
 * The one place money is taken.
 *
 * <p>Everything the product does with a payment — granting a purchase, opening a subscription
 * window, renewing one — goes through this single call, so the day a real provider is introduced
 * there is exactly one implementation to write and no caller to change.
 *
 * <p>There is currently one implementation, {@link SimulatedPaymentGateway}, and it moves no money.
 * That is a deliberate limit, not a stub waiting to be filled with a second provider: this
 * interface is a seam, not a provider abstraction layer.
 */
public interface PaymentGateway {

    /**
     * @throws com.manara.backend.common.exception.BusinessException when the instrument is missing
     *                                                               or malformed
     */
    PaymentReceipt charge(PaymentCharge charge, PaymentMethodRequest paymentMethod);
}
