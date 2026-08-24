package com.manara.backend.common.security.ratelimit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the rate limiting filter, following the same feature-local configuration pattern as
 * {@code EmailConfiguration} and {@code UploadConfiguration}.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.rate-limit", name = "enabled", havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimiter rateLimiter, RateLimitProperties properties) {

        var registration = new FilterRegistrationBean<>(new RateLimitFilter(rateLimiter, properties.rules()));
        // Ahead of Spring Security's chain: the endpoints this protects are the unauthenticated
        // ones, so waiting until after authentication would be too late to be useful.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        properties.rules().forEach(rule ->
                log.info("Rate limit: {} {} -> {} per {}",
                        rule.method() == null ? "ANY" : rule.method(), rule.pattern(), rule.limit(), rule.window()));
        return registration;
    }
}
