package com.manara.backend.auth.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * Owns the auth slice of the security policy: contributes its own public-endpoint
 * allowlist (so users can register / log in / reset password without a session) and
 * assembles the authorize-requests {@link Customizer} from every
 * {@link PublicEndpointContribution} in the context. SecurityConfig consumes the
 * Customizer bean — features stay isolated, the filter chain stays single-source.
 */
@Configuration
public class AuthSecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/register"),
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/verify-otp"),
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/resend-otp"),
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/login"),
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/forgot-password"),
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/verify-reset-otp"),
                PublicEndpoint.of(HttpMethod.POST, "/api/v1/auth/reset-password"),
                PublicEndpoint.of(HttpMethod.GET, "/api/v1/auth/csrf"));
    }

    @Bean
    public Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>
            authorizeHttpRequestsCustomizer(List<PublicEndpointContribution> contributions) {
        RequestMatcher[] publicMatchers = contributions.stream()
                .flatMap(c -> c.endpoints().stream())
                .map(PublicEndpoint::toMatcher)
                .toArray(RequestMatcher[]::new);

        return auth -> auth
                .requestMatchers(publicMatchers)
                .permitAll();
    }

    /**
     * Composes every feature-contributed authorize-requests {@link Customizer} and
     * appends the terminal {@code anyRequest().authenticated()} rule. {@code anyRequest()}
     * must be the final matcher in the chain, so it lives here — features only contribute
     * {@code requestMatchers(...).permitAll()} (or role rules) and never call it themselves.
     */
    public static Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry>
            compose(List<Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry>> contributors) {
        return auth -> {
            contributors.forEach(c -> c.customize(auth));
            auth.anyRequest().authenticated();
        };
    }
}
