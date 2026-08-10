package com.manara.backend.email.config;

import com.manara.backend.email.provider.resend.ResendProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the email feature's typed configuration. Feature-local, so the application class stays
 * untouched as more email settings appear.
 */
@Configuration
@EnableConfigurationProperties({EmailProperties.class, ResendProperties.class})
public class EmailConfiguration {
}
