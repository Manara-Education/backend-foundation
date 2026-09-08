package com.manara.backend.payment.config;

/**
 * Whether this deployment is taking real money.
 *
 * <p>The distinction has to be configuration rather than an inference, because the only thing that
 * currently distinguishes the two is a comment. {@code SimulatedPaymentGateway} returns a successful
 * receipt for every charge without contacting anybody, and checkout grants a paid entitlement from
 * that receipt. As a demonstration that is exactly right. In a deployment that believes it is
 * selling courses, it is a way to be given a paid entitlement for nothing.
 *
 * <p>Nothing in the code could previously tell those two deployments apart. This enum is what makes
 * the difference statable, and {@link CommerceConfig} is what makes it enforceable.
 */
public enum CommerceMode {

    /**
     * Payments are simulated. No money moves, and the platform says so in its logs at startup and on
     * every charge.
     *
     * <p>This is the current, deliberate state of the product. It is the default so that this change
     * does not alter the behaviour of any running deployment — the gate exists to stop a future
     * deployment from taking money in this mode by accident, not to switch anything off today.
     */
    DEMONSTRATION,

    /**
     * Real payments. Entitlements may only be granted against a receipt from a real payment
     * provider.
     *
     * <p>No such provider is integrated yet, so selecting this mode currently stops the application
     * from starting. That is the intended behaviour and the point of the mode: a deployment that
     * claims to be selling courses and has nothing to take payment with must not serve traffic, and
     * it must certainly not fall back to the simulator. Integrating a provider is a separate piece
     * of work; this is the socket it plugs into.
     */
    LIVE
}
