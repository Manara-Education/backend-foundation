package com.manara.backend.payment.dto;

import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The payment instrument a learner submits at checkout.
 *
 * <p><strong>This deliberately carries no card data.</strong> It used to hold {@code cardNumber},
 * {@code expiry} and {@code cvc}, and those fields were removed on purpose.
 *
 * <p>There is no payment provider behind this application — {@link
 * com.manara.backend.payment.service.SimulatedPaymentGateway} contacts nobody and moves no money.
 * So every card number and CVC that reached this object was a real primary account number,
 * transmitted to and held in the memory of a server with no acquirer, no tokenisation, no PCI DSS
 * scope and no reason whatsoever to possess it. Card-shape validation against a gateway that
 * takes no payment was theatre; the risk of holding the data was not.
 *
 * <p>What remains is the learner's contact details, and {@code token} for the day a real provider
 * arrives — at which point that becomes the provider's opaque token and card data still never
 * touches this server, because the client exchanges it with the provider directly.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodRequest {

    /**
     * Opaque instrument token from a payment provider. Unused while the gateway is simulated;
     * present so introducing a real provider does not change this contract's shape.
     */
    private String token;

    private String name;

    @Email
    private String email;
}
