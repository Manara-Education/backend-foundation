package com.manara.backend.payment.config;

import com.manara.backend.ManaraBackendApplication;
import com.manara.backend.db.AbstractPostgresBackedTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SEC-D01. LIVE with no payment provider, started as the real application against real PostgreSQL and
 * Redis rather than a hand-assembled context.
 *
 * <p>{@link CommerceModeTest} proves the guard exists. Only a full start shows which failure an
 * operator actually reads: other beans also notice a missing gateway — checkout's constructor among
 * them — and whichever is created first decides the message. This holds that message to the guard's.
 *
 * <p>The containers are the shared ones, read from the context this class belongs to. The application
 * under test is a second, separate start with LIVE on its command line, which outranks every property
 * file the way a deployment's environment does.
 */
class LiveModeStartupTest extends AbstractPostgresBackedTest {

    @Autowired Environment environment;

    @Test
    @DisplayName("LIVE with no payment provider refuses to start, and the reason given is the guard's")
    void liveWithoutAProviderFailsWithTheGuardsMessage() {
        SpringApplication application = new SpringApplication(ManaraBackendApplication.class);
        ConfigurableApplicationContext[] started = new ConfigurableApplicationContext[1];
        try {
            assertThatThrownBy(() -> started[0] = application.run(
                    "--manara.commerce.mode=LIVE",
                    "--server.port=0",
                    "--spring.datasource.url=" + environment.getProperty("spring.datasource.url"),
                    "--spring.datasource.username=" + environment.getProperty("spring.datasource.username"),
                    "--spring.datasource.password=" + environment.getProperty("spring.datasource.password"),
                    "--spring.data.redis.host=" + environment.getProperty("spring.data.redis.host"),
                    "--spring.data.redis.port=" + environment.getProperty("spring.data.redis.port")))
                    .satisfies(thrown -> assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                            .as("the first failure an operator reads must be the LIVE guard's, not a missing bean")
                            .hasMessageContaining("no payment provider is configured"));
        } finally {
            if (started[0] != null) {
                started[0].close();
            }
        }
    }
}
