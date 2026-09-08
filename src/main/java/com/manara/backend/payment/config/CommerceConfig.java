package com.manara.backend.payment.config;

import com.manara.backend.payment.service.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Holds the platform to whichever commerce mode it was configured for.
 *
 * <p>The rule is one sentence: a simulated receipt may only grant an entitlement in a deployment
 * that has explicitly declared itself a demonstration. Everything here exists to make that
 * unavoidable rather than conventional.
 *
 * <p>The check runs at startup rather than at checkout. A deployment configured to take real money
 * with nothing to take it with is misconfigured before the first learner arrives, and the useful
 * moment to say so is the deploy — not the first purchase, discovered by whoever was trying to make
 * it. Refusing to start is also the only outcome that cannot be missed.
 */
@Slf4j
@Configuration
public class CommerceConfig {

    private final CommerceMode mode;
    private final ObjectProvider<PaymentGateway> gateways;

    public CommerceConfig(@Value("${manara.commerce.mode:DEMONSTRATION}") CommerceMode mode,
                          ObjectProvider<PaymentGateway> gateways) {
        this.mode = mode;
        this.gateways = gateways;
        assertGatewayMatchesMode();
    }

    private void assertGatewayMatchesMode() {
        if (mode != CommerceMode.LIVE) {
            return;
        }

        // In LIVE mode the simulator is not registered, so this is empty until a real provider is
        // integrated. Failing here is the whole safety property: without it, a LIVE deployment
        // would start, reach checkout, and then have to decide what to do with no gateway -- and
        // every convenient answer to that question ends in granting access for nothing.
        if (gateways.getIfAvailable() == null) {
            throw new IllegalStateException("""
                    manara.commerce.mode=LIVE, but no payment provider is configured.

                    LIVE means entitlements may only be granted against a receipt from a real
                    payment provider, and none is integrated yet. The application will not start in
                    this mode, deliberately: a deployment that believes it is selling courses must
                    not serve traffic with no way to take payment, and must never fall back to the
                    simulator.

                    Either integrate a PaymentGateway implementation, or set
                    manara.commerce.mode=DEMONSTRATION if this deployment is meant to demonstrate
                    the purchase flow without taking money.""");
        }
    }

    /**
     * Says out loud, once per boot, which mode this deployment is in.
     *
     * <p>At WARN in demonstration mode on purpose. A platform that is granting course access for
     * simulated payments should be saying so somewhere an operator will actually see it, and the
     * cost of that line being noisy is much lower than the cost of nobody knowing.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void announceMode() {
        if (mode == CommerceMode.DEMONSTRATION) {
            log.warn("COMMERCE MODE: DEMONSTRATION — payments are simulated, no money moves, "
                    + "and paid entitlements are granted against simulated receipts. "
                    + "Set manara.commerce.mode=LIVE (with a real payment provider) before selling.");
        } else {
            log.info("COMMERCE MODE: LIVE — entitlements require a receipt from a real payment provider.");
        }
    }
}
