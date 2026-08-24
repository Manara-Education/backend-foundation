package com.manara.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * App-wide security customizers — CSRF (cookie-based double-submit token) and the 401
 * entry point for unauthenticated API calls. SecurityConfig wires these into the chain.
 */
@Configuration
public class WebSecurityCustomizers {

    /** XSRF-TOKEN cookie readable by JS, echoed back as X-XSRF-TOKEN on state-changing requests. */
    @Bean
    public Customizer<CsrfConfigurer<HttpSecurity>> csrfCustomizer() {
        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // resolve token eagerly so cookie is issued

        return csrf -> csrf
                .csrfTokenRepository(csrfRepo)
                .csrfTokenRequestHandler(csrfHandler);
    }

    /** Return 401 instead of redirecting to a login page — this is an API, not a server-rendered app. */
    @Bean
    public Customizer<ExceptionHandlingConfigurer<HttpSecurity>> exceptionHandlingCustomizer() {
        return ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED));
    }
}
