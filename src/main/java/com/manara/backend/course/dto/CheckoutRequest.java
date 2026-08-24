package com.manara.backend.course.dto;

import com.manara.backend.payment.dto.PaymentMethodRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a learner submits to gain access to a course.
 *
 * <p>The body is shaped by the course's own access type, and the server decides which parts it
 * reads — a client cannot promote itself to a cheaper path by leaving fields out:
 *
 * <ul>
 *   <li><strong>FREE</strong> — {@code {}}. Anything else present is ignored.</li>
 *   <li><strong>PURCHASE</strong> — {@code {"paymentMethod": {...}}}. The amount is the course's
 *       stored price; nothing in this payload can influence it.</li>
 *   <li><strong>SUBSCRIPTION</strong> — {@code {"planId": 15, "paymentMethod": {...}}}. Only the
 *       plan's identifier is trusted: its price, duration and expiry are read from the plan row
 *       after confirming the plan belongs to this course.</li>
 * </ul>
 *
 * <p>There is deliberately no field for a price, an amount or an expiry date. Every one of those is
 * computed server-side.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {

    /** Required for a {@code SUBSCRIPTION} course, ignored for the other two. */
    private Long planId;

    @Valid
    private PaymentMethodRequest paymentMethod;

    // ── Previous contract ─────────────────────────────────────────────────────
    // The card fields (cardNumber, expiry, cvc) that used to sit at the top level are GONE, along
    // with the same fields inside PaymentMethodRequest. There is no payment provider behind this
    // application, so those values were real card numbers and CVCs arriving at a server with no
    // acquirer and no PCI DSS scope, to be checked for plausible shape and then discarded.
    //
    // Nothing breaks by removing them: the client sends the nested `paymentMethod` object, and
    // Jackson ignores unknown properties, so a client still transmitting the old fields simply has
    // them dropped rather than rejected.

}
