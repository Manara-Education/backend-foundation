package com.manara.backend.swagger.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * Owns the swagger slice of the security policy: exposes the OpenAPI spec and the
 * Swagger UI assets without authentication. Kept in the swagger feature module so
 * common/ stays free of feature-specific public endpoints.
 *
 * <p><strong>Not registered in production.</strong> {@code springdoc.api-docs.enabled} and
 * {@code springdoc.swagger-ui.enabled} are already false there (application-prod.properties), so
 * there is nothing behind these paths to expose — but leaving them on the public allow-list would
 * mean that re-enabling springdoc for any reason silently republishes a complete map of every
 * endpoint, parameter and schema, plus a live request console pointed at the production API. Two
 * independent switches have to be thrown, not one.
 */
@Configuration
@Profile("!prod")
public class SwaggerSecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(
                PublicEndpoint.of(HttpMethod.GET, "/v3/api-docs"),
                PublicEndpoint.of(HttpMethod.GET, "/v3/api-docs/**"),
                PublicEndpoint.of(HttpMethod.GET, "/v3/api-docs.yaml"),
                PublicEndpoint.of(HttpMethod.GET, "/swagger-ui.html"),
                PublicEndpoint.of(HttpMethod.GET, "/swagger-ui/**"),
                PublicEndpoint.of(HttpMethod.GET, "/webjars/**"));
    }
}
