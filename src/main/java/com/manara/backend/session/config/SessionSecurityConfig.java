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
 * the {@link Customizer} bean below so that all session policy (creation, fixation defense,
 * concurrency limits) lives next to the SessionManager and SessionConfig.
 */
@Configuration
public class SessionSecurityConfig {

    private static final int MAX_CONCURRENT_SESSIONS = 5;

    @Bean
    public Customizer<SessionManagementConfigurer<HttpSecurity>> sessionManagementCustomizer() {
        return sees -> sees
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(SessionManagementConfigurer.SessionFixationConfigurer::changeSessionId)
                .maximumSessions(MAX_CONCURRENT_SESSIONS)
                .maxSessionsPreventsLogin(false);
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
