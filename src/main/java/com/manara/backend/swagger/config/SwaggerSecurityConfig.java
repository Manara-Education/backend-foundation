package com.manara.backend.swagger.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * Owns the swagger slice of the security policy: exposes the OpenAPI spec and the
 * Swagger UI assets without authentication. Kept in the swagger feature module so
 * common/ stays free of feature-specific public endpoints.
 */
@Configuration
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
