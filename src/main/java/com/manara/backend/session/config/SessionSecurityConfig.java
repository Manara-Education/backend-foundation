package com.manara.backend.session.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Owns the session-management slice of the security filter chain. SecurityConfig consumes
 * the {@link Customizer} bean below so that all session policy (creation, fixation defense)
 * lives next to the SessionManager and SessionConfig.
 *
 * <p>There is deliberately no {@code maximumSessions} here. It configures Spring Security's
 * concurrency control, which only Spring Security's own authentication filters ever invoke, and
 * which keeps its register in memory. Sign-in here never passes through those filters, so the
 * setting limited nothing while reading as though it did. The limit is enforced by
 * {@link com.manara.backend.session.manager.SessionCeiling}.
 */
@Configuration
public class SessionSecurityConfig {

    @Bean
    public Customizer<SessionManagementConfigurer<HttpSecurity>> sessionManagementCustomizer() {
        return sees -> sees
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId);
    }

    @Bean
    public ChangeSessionIdAuthenticationStrategy sessionFixationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public HttpSessionSecurityContextRepository sessionSecurityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }
}
