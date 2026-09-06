package com.manara.backend.common.file;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Who may put a file into the public upload directory.
 *
 * <p>Uploading was covered only by the chain's terminal {@code anyRequest().authenticated()} rule,
 * so every signed-in account could use it — including students, who have no upload feature. The
 * directory it writes to is served publicly at {@code /uploads/**} and shares the host's disk with
 * the application, so that amounted to general-purpose file hosting for anyone who could register.
 *
 * <p>Both consumers are instructor tools: the course cover picker and the banner editor. No student
 * or profile-avatar upload exists in the frontend, so restricting this to INSTRUCTOR closes the hole
 * without closing a shipped flow. {@code RateLimitProperties} already described this endpoint as
 * instructor-only; this is the rule that makes the description true.
 *
 * <p>Contributed as a customizer rather than written into the shared chain, which is the extension
 * point {@code SecurityConfig} injects and {@code AuthSecurityConfig.compose} appends
 * {@code anyRequest()} after — so this deliberately does not call {@code anyRequest()} itself.
 *
 * <p>Placing the rule here, in the filter chain, means a rejected request is refused before
 * {@code DispatcherServlet} resolves the multipart. Nothing is parsed and no temporary file is
 * written for a caller who was never allowed to upload. The service checks the role again anyway —
 * see {@link FileUploadService#storeFile} — because a URL rule is a statement about one route,
 * and the rule that matters belongs next to the filesystem write.
 */
@Configuration
public class UploadSecurityConfig {

    @Bean
    public Customizer<AuthorizeHttpRequestsConfigurer<HttpSecurity>
            .AuthorizationManagerRequestMatcherRegistry> uploadAuthorizeCustomizer() {
        return auth -> auth
                .requestMatchers(HttpMethod.POST, "/api/v1/uploads")
                .hasRole("INSTRUCTOR");
    }
}
