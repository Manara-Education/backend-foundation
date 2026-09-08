package com.manara.backend.email.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * The thread pool outbound email is sent on.
 *
 * <p>Declared rather than defaulted. Spring's fallback for {@code @Async} work is an executor that
 * starts a new thread per task and bounds nothing, which turns a slow provider into unbounded thread
 * growth on a container that has a memory limit — the outage becomes an outage of the whole
 * application rather than of email.
 *
 * <p>The numbers are small on purpose. Email here is a handful of one-time codes, not a campaign,
 * and the provider is a third party with its own rate limits. A queue this size absorbs a burst of
 * sign-ups; anything beyond it is shed loudly, in the dispatcher, rather than silently accumulating.
 */
@Configuration
public class EmailDispatchConfig {

    @Value("${email.dispatch.core-pool-size:2}")
    private int corePoolSize;

    @Value("${email.dispatch.max-pool-size:4}")
    private int maxPoolSize;

    @Value("${email.dispatch.queue-capacity:100}")
    private int queueCapacity;

    @Bean(name = "emailDispatchExecutor")
    public Executor emailDispatchExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("email-dispatch-");

        // Shed rather than block. The alternative policy runs the task on the caller's thread, which
        // would put the provider call back on the request thread under load -- reintroducing exactly
        // the timing difference this indirection exists to remove, and only when the system is
        // busiest.
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

        // Let in-flight sends finish on shutdown, briefly. A code already promised to somebody is
        // worth a few seconds; a deploy is not worth stranding it.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);

        executor.initialize();
        return executor;
    }
}
