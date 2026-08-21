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
 * <p>Deliberately card-shaped and nothing more. There is no payment provider behind this
 * application, so there is no tokenization step to model and no provider-specific payload to carry.
 * When a real provider arrives this becomes the client's opaque token and the fields below
 * disappear — which is why nothing outside {@link com.manara.backend.payment} reads them.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentMethodRequest {

    private String cardNumber;

    /** {@code MM/YY} or {@code MM / YY}, as the checkout form produces it. */
    private String expiry;

    private String cvc;

    private String name;

    @Email
    private String email;
}
