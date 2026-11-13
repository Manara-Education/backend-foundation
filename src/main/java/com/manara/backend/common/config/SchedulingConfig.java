package com.manara.backend.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}. The only scheduled work today is data retention
 * ({@code OtpRetentionPurger}). Production runs a single backend instance; every job must stay
 * idempotent so a second instance running it as well would do no harm.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
