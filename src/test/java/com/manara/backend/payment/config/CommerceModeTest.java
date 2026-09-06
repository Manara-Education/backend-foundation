package com.manara.backend.payment.config;

import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.payment.service.SimulatedPaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MANARA-SEC-015. Which deployments are allowed to grant a paid entitlement for a simulated payment.
 *
 * <p>The gate is a wiring decision, so these are wiring tests: they start real Spring contexts with
 * real conditions and ask what got registered. Asserting on the annotation instead would only prove
 * that the annotation is written down.
 */
class CommerceModeTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class)
            .withBean(SimulatedPaymentGatewayRegistration.class);

    @Test
    @DisplayName("an unconfigured deployment stays exactly as it is today: demonstration")
    void theDefaultIsDemonstration() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("no configuration must mean no behaviour change for a running deployment")
                    .hasSingleBean(PaymentGateway.class);
            assertThat(context.getBean(PaymentGateway.class))
                    .isInstanceOf(SimulatedPaymentGateway.class);
        });
    }

    @Test
    @DisplayName("demonstration mode, stated explicitly, registers the simulator")
    void demonstrationRegistersTheSimulator() {
        contexts.withPropertyValues("manara.commerce.mode=DEMONSTRATION").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PaymentGateway.class))
                    .isInstanceOf(SimulatedPaymentGateway.class);
        });
    }

    @Test
    @DisplayName("live mode does not get the simulator, whatever else happens")
    void liveNeverGetsTheSimulator() {
        contexts.withPropertyValues("manara.commerce.mode=LIVE").run(context ->
                assertThat(context.getBeansOfType(SimulatedPaymentGateway.class))
                        .as("a real-money deployment must not be served by the simulator")
                        .isEmpty());
    }

    @Test
    @DisplayName("live mode with no payment provider refuses to start")
    void liveWithoutAProviderFailsFast() {
        contexts.withUserConfiguration(CommerceConfig.class)
                .withPropertyValues("manara.commerce.mode=LIVE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("the failure must say what is wrong and how to resolve it")
                            .hasStackTraceContaining("no payment provider is configured");
                });
    }

    @Test
    @DisplayName("live mode with a real provider starts, and uses that provider")
    void liveWithAProviderStarts() {
        contexts.withUserConfiguration(CommerceConfig.class, RealProviderConfig.class)
                .withPropertyValues("manara.commerce.mode=LIVE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentGateway.class))
                            .isNotInstanceOf(SimulatedPaymentGateway.class);
                });
    }

    @Test
    @DisplayName("demonstration mode still refuses a charge with no payment instrument")
    void demonstrationStillRequiresAnInstrument() {
        contexts.withPropertyValues("manara.commerce.mode=DEMONSTRATION").run(context -> {
            PaymentGateway gateway = context.getBean(PaymentGateway.class);
            assertThat(gateway).isInstanceOf(SimulatedPaymentGateway.class);

            // The learner's explicit "yes, proceed" is still required. Gating the mode must not
            // quietly relax what the simulator itself checks.
            assertThat(catchThrowable(() -> gateway.charge(
                    new PaymentCharge(null, "test", "key-1"), null)))
                    .isNotNull();
        });
    }

    @Test
    @DisplayName("the real component carries the real condition")
    void theProductionBeanIsActuallyConditioned() {
        // The contexts above register the gateway through a stand-in so the condition can be varied
        // per test. That would keep passing if somebody deleted the annotation from the component
        // itself, which is the one change that would silently re-open the finding — so the real
        // class is read here and held to the same condition the stand-in uses.
        ConditionalOnProperty condition =
                SimulatedPaymentGateway.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition)
                .as("SimulatedPaymentGateway must not be registered unconditionally")
                .isNotNull();
        assertThat(condition.name()).containsExactly("manara.commerce.mode");
        assertThat(condition.havingValue()).isEqualTo("DEMONSTRATION");
        assertThat(condition.matchIfMissing())
                .as("an existing deployment that sets nothing must keep working unchanged")
                .isTrue();
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Configuration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    /**
     * Registers the real {@link SimulatedPaymentGateway} under its real condition, so the condition
     * itself is what these tests exercise.
     */
    @Configuration
    static class SimulatedPaymentGatewayRegistration {
        @Bean
        @ConditionalOnProperty(
                name = "manara.commerce.mode",
                havingValue = "DEMONSTRATION",
                matchIfMissing = true)
        SimulatedPaymentGateway simulatedPaymentGateway(Clock clock) {
            return new SimulatedPaymentGateway(clock);
        }
    }

    /** Stands in for a payment provider integration that does not exist yet. */
    @Configuration
    static class RealProviderConfig {
        @Bean
        PaymentGateway realProvider() {
            return (PaymentCharge charge, PaymentMethodRequest method) -> {
                throw new UnsupportedOperationException("not a real provider, only a stand-in");
            };
        }
    }
}
