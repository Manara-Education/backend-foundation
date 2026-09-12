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
     * No payment is taken and none is simulated. Free courses enrol as normal; a paid checkout is
     * refused with {@code PAYMENTS_UNAVAILABLE} before anything is written.
     *
     * <p>The mode for a production deployment with no payment provider. The other two each fail such
     * a deployment: DEMONSTRATION stays up by unlocking paid courses for simulated payments, and LIVE
     * refuses to start. This one stays up without selling. A gateway that refuses every charge stands
     * in for a provider so the application can start; checkout refuses before it is ever asked.
     */
    FREE_ONLY,

    /**
     * Payments are simulated. No money moves, and the platform says so in its logs at startup, on
     * every charge, and in every checkout response it grants from a simulated receipt
     * ({@code simulated: true}).
     *
     * <p>This is the current, deliberate state of the product, and the default for local development
     * and tests. It is not a default in production: under the {@code prod} profile an unset mode
     * refuses to start. Inheriting it there is how production came to grant paid courses for
     * simulated payments with nobody having chosen to (pentest, 2026-09-10), so a production
     * deployment in this mode is now one that said so.
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
