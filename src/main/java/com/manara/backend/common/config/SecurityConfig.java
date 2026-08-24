package com.manara.backend.common.config;

import com.manara.backend.auth.config.AuthSecurityConfig;
import com.manara.backend.auth.security.PasswordResetRequiredFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AuthenticationProvider authenticationProvider;
    private final Customizer<CsrfConfigurer<HttpSecurity>> csrfCustomizer;
    private final Customizer<SessionManagementConfigurer<HttpSecurity>> sessionManagementCustomizer;
    private final Customizer<ExceptionHandlingConfigurer<HttpSecurity>> exceptionHandlingCustomizer;
    private final PasswordResetRequiredFilter passwordResetRequiredFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            List<Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry>> authorizeCustomizers
    ) throws Exception {
        return http
                .csrf(csrfCustomizer)
                .authorizeHttpRequests(AuthSecurityConfig.compose(authorizeCustomizers))
                .sessionManagement(sessionManagementCustomizer)
                .exceptionHandling(exceptionHandlingCustomizer)
                // After AuthorizationFilter: the request has already been authenticated and
                // allowed, and only then is it asked whether the account still owes a password
                // change. Anonymous and public traffic never reaches the check.
                .addFilterAfter(passwordResetRequiredFilter, AuthorizationFilter.class)
                .logout(AbstractHttpConfigurer::disable)
                .authenticationProvider(authenticationProvider)
                .build();
    }
}
