package com.manara.backend.payment.config;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.payment.service.PaymentsUnavailableGateway;
import com.manara.backend.payment.service.SimulatedPaymentGateway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.ResourcePropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MANARA-SEC-015. Which deployments are allowed to grant a paid entitlement for a simulated payment.
 *
 * <p>The gate is a wiring decision, so these are wiring tests: they start real Spring contexts with
 * real conditions and ask what got registered. Asserting on the annotation instead would only prove
 * that the annotation is written down.
 */
@ExtendWith(OutputCaptureExtension.class)
class CommerceModeTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(ClockConfig.class, CommerceConfig.class)
            .withBean(SimulatedPaymentGatewayRegistration.class);

    @Test
    @DisplayName("outside production, an unconfigured context is a demonstration")
    void theDefaultIsDemonstration() {
        contexts.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context)
                    .as("no configuration outside production must keep development and tests unchanged")
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
    @DisplayName("a blank mode fails clearly instead of silently losing the simulator")
    void aBlankModeFailsClearly() {
        // The simulator's condition does not match a blank value. Before the mode was resolved in one
        // place, that surfaced as a missing PaymentGateway at checkout's constructor.
        contexts.withPropertyValues("manara.commerce.mode=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("manara.commerce.mode is blank");
        });
    }

    @Test
    @DisplayName("live mode does not get the simulator, whatever else happens")
    void liveNeverGetsTheSimulator() {
        // Without CommerceConfig, whose LIVE guard would stop the context before it could be asked.
        new ApplicationContextRunner()
                .withUserConfiguration(ClockConfig.class)
                .withBean(SimulatedPaymentGatewayRegistration.class)
                .withPropertyValues("manara.commerce.mode=LIVE")
                .run(context -> assertThat(context.getBeansOfType(SimulatedPaymentGateway.class))
                        .as("a real-money deployment must not be served by the simulator")
                        .isEmpty());
    }

    @Test
    @DisplayName("live mode with no payment provider refuses to start")
    void liveWithoutAProviderFailsFast() {
        contexts.withPropertyValues("manara.commerce.mode=LIVE")
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
        contexts.withUserConfiguration(RealProviderConfig.class)
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
                .as("development and tests that set nothing keep the simulator; production cannot set nothing")
                .isTrue();
    }

    // --- production states its mode (SEC-D01) --------------------------------
    //
    // The pentest of 2026-09-10 found production running DEMONSTRATION because nothing set the mode
    // and application.properties defaulted it. That is a fact about the property files, so these
    // contexts load the real application.properties and application-prod.properties rather than a
    // hand-written property that would agree with whatever the test expected. The real simulator
    // class is registered, so its own condition is the one evaluated.

    @Test
    @DisplayName("production with no commerce mode refuses to start, and says what to set")
    void productionWithoutAModeFailsFast() {
        production(Map.of()).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("must be set explicitly in production")
                    .hasStackTraceContaining("MANARA_COMMERCE_MODE")
                    .hasStackTraceContaining("The safe choice for a production deployment with no payment");
        });
    }

    @Test
    @DisplayName("production with a blank commerce mode refuses to start")
    void productionWithABlankModeFailsFast() {
        production(Map.of("MANARA_COMMERCE_MODE", "")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("must be set explicitly in production");
        });
    }

    @Test
    @DisplayName("production with a value that is not a mode refuses to start, naming the valid ones")
    void productionWithAnInvalidModeFailsFast() {
        production(Map.of("MANARA_COMMERCE_MODE", "DEMO")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("'DEMO' is not a commerce mode")
                    .hasStackTraceContaining("FREE_ONLY, DEMONSTRATION or LIVE");
        });
    }

    @Test
    @DisplayName("production that sets no mode never registers the simulator, guard or no guard")
    void productionWithoutAModeNeverGetsTheSimulator() {
        // CommerceConfig deliberately absent: the simulator's own condition must already keep it out,
        // so removing the startup guard could not quietly put the simulator back in production.
        new ApplicationContextRunner(overPropertyFiles(Map.of(), "prod"))
                .withUserConfiguration(ClockConfig.class, SimulatedPaymentGateway.class)
                .run(context -> assertThat(context.getBeansOfType(SimulatedPaymentGateway.class))
                        .as("an unset mode in production must not fall through to the simulator")
                        .isEmpty());
    }

    @Test
    @DisplayName("production that states DEMONSTRATION starts, uses the simulator, and warns")
    void productionInDemonstrationStartsAndWarns(CapturedOutput output) {
        production(Map.of("MANARA_COMMERCE_MODE", "DEMONSTRATION")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CommerceMode.class)).isEqualTo(CommerceMode.DEMONSTRATION);
            assertThat(context.getBean(PaymentGateway.class)).isInstanceOf(SimulatedPaymentGateway.class);

            context.publishEvent(new ApplicationReadyEvent(new SpringApplication(), new String[0],
                    context.getSourceApplicationContext(), Duration.ZERO));
            assertThat(output).contains("COMMERCE MODE: DEMONSTRATION");
        });
    }

    @Test
    @DisplayName("production that states LIVE with no payment provider still refuses to start")
    void productionLiveWithoutAProviderStillFails() {
        production(Map.of("MANARA_COMMERCE_MODE", "LIVE")).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("no payment provider is configured");
        });
    }

    @Test
    @DisplayName("outside production an unset mode is still DEMONSTRATION, published as one bean")
    void developmentKeepsTheDemonstrationDefault() {
        new ApplicationContextRunner(overPropertyFiles(Map.of()))
                .withUserConfiguration(ClockConfig.class, CommerceConfig.class, SimulatedPaymentGateway.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(CommerceMode.class)).isEqualTo(CommerceMode.DEMONSTRATION);
                    assertThat(context.getBean(PaymentGateway.class))
                            .isInstanceOf(SimulatedPaymentGateway.class);
                });
    }

    @Test
    @DisplayName("production that states FREE_ONLY starts with no provider and no simulator, and refuses every charge")
    void productionInFreeOnlyStartsAndSellsNothing() {
        production(Map.of("MANARA_COMMERCE_MODE", "FREE_ONLY")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CommerceMode.class)).isEqualTo(CommerceMode.FREE_ONLY);
            assertThat(context.getBeansOfType(SimulatedPaymentGateway.class)).isEmpty();

            PaymentGateway gateway = context.getBean(PaymentGateway.class);
            assertThat(gateway).isInstanceOf(PaymentsUnavailableGateway.class);
            assertThatThrownBy(() -> gateway.charge(
                    new PaymentCharge(BigDecimal.TEN, "test", "key-1"), new PaymentMethodRequest()))
                    .hasMessage("error.payment.unavailable")
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                            .isEqualTo(ErrorCode.PAYMENTS_UNAVAILABLE));
        });
    }

    @Test
    @DisplayName("LIVE never counts the simulator or the refusing gateway as a payment provider")
    void liveNeverFallsBackToEitherStandIn() {
        // Both registered with no condition, as if their conditions had been deleted. The guard must
        // still refuse, because neither takes a real payment.
        new ApplicationContextRunner()
                .withUserConfiguration(ClockConfig.class, CommerceConfig.class, UnconditionalStandIns.class)
                .withPropertyValues("manara.commerce.mode=LIVE")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("no payment provider is configured");
                });
    }

    private ApplicationContextRunner production(Map<String, Object> deploymentVariables) {
        return new ApplicationContextRunner(overPropertyFiles(deploymentVariables, "prod"))
                .withUserConfiguration(ClockConfig.class, CommerceConfig.class, SimulatedPaymentGateway.class,
                        PaymentsUnavailableGateway.class);
    }

    /**
     * A context whose environment is the real property files for these profiles, plus exactly the
     * given process environment, in the precedence Spring Boot gives them. The developer's own shell
     * and JVM properties are removed, so a MANARA_COMMERCE_MODE exported locally cannot pass a test
     * that production would fail.
     */
    private static Supplier<ConfigurableApplicationContext> overPropertyFiles(
            Map<String, Object> deploymentVariables, String... profiles) {
        return () -> {
            StandardEnvironment environment = new StandardEnvironment();
            MutablePropertySources sources = environment.getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            sources.addLast(new SystemEnvironmentPropertySource("deployment", deploymentVariables));
            for (String profile : profiles) {
                sources.addLast(propertiesFile("application-" + profile + ".properties"));
            }
            sources.addLast(propertiesFile("application.properties"));
            environment.setActiveProfiles(profiles);

            AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
            context.setEnvironment(environment);
            return context;
        };
    }

    private static PropertySource<?> propertiesFile(String name) {
        try {
            return new ResourcePropertySource(name, new ClassPathResource(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        SimulatedPaymentGateway simulatedPaymentGateway(Clock clock, CommerceMode mode) {
            return new SimulatedPaymentGateway(clock, mode);
        }
    }

    /** The simulator and the refusing gateway, registered with no condition at all. */
    @Configuration
    static class UnconditionalStandIns {
        @Bean
        SimulatedPaymentGateway simulator(Clock clock, CommerceMode mode) {
            return new SimulatedPaymentGateway(clock, mode);
        }

        @Bean
        PaymentsUnavailableGateway refusingGateway() {
            return new PaymentsUnavailableGateway();
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
