package com.manara.backend.payment.config;

import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.payment.service.PaymentsUnavailableGateway;
import com.manara.backend.payment.service.SimulatedPaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Locale;

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
 *
 * <p>Startup is the first refusal, not the only one. The simulator will not charge, and checkout will
 * not grant from a simulated receipt, outside DEMONSTRATION — and both read the one {@link CommerceMode}
 * bean published here, so a wiring mistake that got past startup still grants nothing.
 */
@Slf4j
@Configuration
public class CommerceConfig {

    private static final String MODE_PROPERTY = "manara.commerce.mode";

    private static final String MODE_REQUIRED_IN_PRODUCTION = """
            manara.commerce.mode is not set, and this deployment runs the prod profile.

            The commerce mode must be set explicitly in production; it is never inferred. Set
            MANARA_COMMERCE_MODE to one of:

              FREE_ONLY      The safe choice for a production deployment with no payment
                             provider. Free courses enrol; paid checkout is refused.
              DEMONSTRATION  Grants simulated paid access: paid courses unlock against
                             simulated receipts and no money moves. Only for a deployment
                             that is openly a demonstration.
              LIVE           Real payments only. No payment provider is integrated yet, so
                             LIVE currently refuses to start as well.

            An unset mode used to mean DEMONSTRATION in production too, which is how production
            came to grant paid courses for simulated payments without anyone having chosen to.""";

    private static final String LIVE_WITHOUT_PROVIDER = """
            manara.commerce.mode=LIVE, but no payment provider is configured.

            LIVE means entitlements may only be granted against a receipt from a real
            payment provider, and none is integrated yet. The application will not start in
            this mode, deliberately: a deployment that believes it is selling courses must
            not serve traffic with no way to take payment, and must never fall back to the
            simulator or to the gateway that refuses every payment.

            Either integrate a PaymentGateway implementation, or set
            manara.commerce.mode=FREE_ONLY to run without paid checkout (the safe choice for a
            deployment with no provider), or DEMONSTRATION if this deployment is meant to
            demonstrate the purchase flow by granting simulated paid access.""";

    private final CommerceMode mode;

    public CommerceConfig(CommerceMode mode) {
        this.mode = mode;
    }

    /**
     * Reads the mode once, holds LIVE to a real provider, and publishes the mode as the
     * {@link CommerceMode} bean.
     *
     * <p>Everything that has to agree about the mode injects that bean rather than reading the
     * property again — the simulator, checkout. A second reading would be a second chance to disagree.
     *
     * <p>A {@code BeanFactoryPostProcessor}, working from bean definitions, so that both refusals come
     * before any bean is created. The LIVE check used to run in this class's constructor, and a full
     * start showed what that cost (LiveModeStartupTest): beans that need a gateway were created first,
     * and the failure an operator read was theirs, not the reason below.
     */
    @Bean
    static BeanFactoryPostProcessor commerceModeResolution(Environment environment) {
        return beanFactory -> {
            CommerceMode resolved = resolveMode(environment);
            if (resolved == CommerceMode.LIVE) {
                assertRealPaymentProvider(beanFactory);
            }
            beanFactory.registerSingleton("commerceMode", resolved);
        };
    }

    private static CommerceMode resolveMode(Environment environment) {
        String configured = environment.getProperty(MODE_PROPERTY);

        if (configured == null || configured.isBlank()) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException(MODE_REQUIRED_IN_PRODUCTION);
            }
            // Absent outside production means a context that loaded no property file at all — a
            // test — and keeps the demonstration default application.properties gives local runs.
            if (configured == null) {
                return CommerceMode.DEMONSTRATION;
            }
            // Blank is refused everywhere: the gateways' conditions do not match a blank value, so
            // reading it as DEMONSTRATION here would leave the two disagreeing.
            throw new IllegalStateException("manara.commerce.mode is blank. Set MANARA_COMMERCE_MODE to "
                    + "FREE_ONLY, DEMONSTRATION or LIVE, or leave it unset to use DEMONSTRATION outside production.");
        }
        try {
            // Case-insensitive, as the gateways' conditions are, for the same reason.
            return CommerceMode.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAMode) {
            throw new IllegalStateException(("manara.commerce.mode='%s' is not a commerce mode. "
                    + "Set MANARA_COMMERCE_MODE to FREE_ONLY, DEMONSTRATION or LIVE.").formatted(configured));
        }
    }

    /**
     * LIVE starts only if some gateway other than the simulator and the refusing gateway is defined.
     * Their conditions already keep both out of LIVE; this does not rely on that. Failing here is the
     * whole safety property: a LIVE deployment that started with no provider would reach checkout and
     * have to decide what to do with no gateway, and every convenient answer ends in granting access
     * for nothing.
     */
    private static void assertRealPaymentProvider(ConfigurableListableBeanFactory beanFactory) {
        boolean realProvider = Arrays.stream(beanFactory.getBeanNamesForType(PaymentGateway.class, true, false))
                .map(name -> beanFactory.getType(name, false))
                .anyMatch(type -> type != null
                        && !SimulatedPaymentGateway.class.isAssignableFrom(type)
                        && !PaymentsUnavailableGateway.class.isAssignableFrom(type));
        if (!realProvider) {
            throw new IllegalStateException(LIVE_WITHOUT_PROVIDER);
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
        switch (mode) {
            case DEMONSTRATION -> log.warn("COMMERCE MODE: DEMONSTRATION — payments are simulated, no money moves, "
                    + "and paid entitlements are granted against simulated receipts. "
                    + "Set manara.commerce.mode=LIVE (with a real payment provider) before selling.");
            case FREE_ONLY -> log.info("COMMERCE MODE: FREE_ONLY — no payments are taken or simulated. "
                    + "Free courses enrol; paid checkout is refused until a payment provider is integrated.");
            case LIVE -> log.info("COMMERCE MODE: LIVE — entitlements require a receipt from a real payment provider.");
        }
    }
}
