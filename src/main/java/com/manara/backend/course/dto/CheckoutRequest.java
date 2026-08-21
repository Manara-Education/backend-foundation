package com.manara.backend.course.dto;

import com.manara.backend.payment.dto.PaymentMethodRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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
    // The card fields used to sit at the top level. They are still accepted so a client deployed
    // against the old shape keeps working, and are folded into `paymentMethod` when that object is
    // absent. New clients send `paymentMethod`.

    /** @deprecated Send {@code paymentMethod.cardNumber}. */
    @Deprecated(since = "enrollment lifecycle")
    private String cardNumber;

    /** @deprecated Send {@code paymentMethod.expiry}. */
    @Deprecated(since = "enrollment lifecycle")
    private String expiry;

    /** @deprecated Send {@code paymentMethod.cvc}. */
    @Deprecated(since = "enrollment lifecycle")
    private String cvc;

    /** @deprecated Send {@code paymentMethod.name}. */
    @Deprecated(since = "enrollment lifecycle")
    private String name;

    /** @deprecated Send {@code paymentMethod.email}. */
    @Deprecated(since = "enrollment lifecycle")
    @Email
    private String email;
}
