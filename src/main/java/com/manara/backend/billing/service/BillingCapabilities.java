package com.manara.backend.billing.service;

import com.manara.backend.billing.dto.BillingCapabilitiesResponse;
import com.manara.backend.payment.config.CommerceMode;
import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.payment.service.PaymentsUnavailableGateway;
import com.manara.backend.payment.service.SimulatedPaymentGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The capability answer, from the commerce mode and the gateway actually wired in.
 *
 * <p>Only the simulator and the refusing gateway exist today, so every provider-backed capability is
 * {@code false}: saved methods, recurring charges, status refresh against a provider and refunds all
 * need an integration that does not exist yet. When one is added, its adapter is what turns these on —
 * not a flag someone can set without it.
 */
@Service
@RequiredArgsConstructor
public class BillingCapabilities {

    private final CommerceMode commerceMode;
    private final PaymentGateway paymentGateway;

    public BillingCapabilitiesResponse current() {
        boolean realProvider = !(paymentGateway instanceof SimulatedPaymentGateway)
                && !(paymentGateway instanceof PaymentsUnavailableGateway);
        boolean checkout = switch (commerceMode) {
            case FREE_ONLY -> false;
            case DEMONSTRATION -> true;
            case LIVE -> realProvider;
        };
        return new BillingCapabilitiesResponse(
                commerceMode.name(),
                null,
                checkout,
                commerceMode == CommerceMode.DEMONSTRATION,
                List.of(),
                false,
                false,
                false,
                false);
    }
}
