package com.manara.backend.auth.config;

import com.manara.backend.auth.password.PasswordPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes {@link PasswordPolicy} to services. Created eagerly, so a build that is missing the
 * bundled blocklist fails at startup instead of on somebody's first password change.
 */
@Configuration
public class PasswordPolicyConfig {

    @Bean
    public PasswordPolicy passwordPolicy() {
        return PasswordPolicy.standard();
    }
}
