package com.manara.backend.common.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * Opens the health endpoint to unauthenticated callers, and nothing else.
 *
 * <p>It has to be reachable without credentials, because the things that ask it — the
 * container's own {@code HEALTHCHECK}, compose's dependency gating, the deployment's
 * post-restart verification and any uptime monitor — have no session and cannot obtain one.
 *
 * <p>That is safe only because of how the endpoint is configured in application.properties:
 * {@code show-details} and {@code show-components} are {@code when-authorized}, so an
 * anonymous caller receives {@code {"status":"UP"}} and learns nothing about which component
 * failed, and every other actuator endpoint is excluded from web exposure. Widening either of
 * those without revisiting this class would start publishing internals — {@code /actuator/env}
 * and {@code /actuator/configprops} print resolved configuration, secrets included.
 *
 * <p>Only GET is listed: the endpoint is read-only, so there is no POST to permit.
 */
@Configuration
public class ActuatorSecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(
                PublicEndpoint.of(HttpMethod.GET, "/actuator/health"),
                // Covers the liveness and readiness probe groups.
                PublicEndpoint.of(HttpMethod.GET, "/actuator/health/**"));
    }
}
