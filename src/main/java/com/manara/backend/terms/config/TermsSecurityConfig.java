package com.manara.backend.terms.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * The terms feature's slice of the security policy.
 *
 * <p>One unauthenticated endpoint, contributed here rather than added to any list in {@code common/}
 * — the feature that owns the route owns the decision to expose it, and
 * {@code AuthSecurityConfig#authorizeHttpRequestsCustomizer} collects every contribution in the
 * context.
 *
 * <p>Public because registration depends on it: the form has to be able to show what it is asking
 * the visitor to accept, and that visitor has no session by definition. Read-only, carrying no
 * personal data and revealing nothing about who is asking — a version identifier and a date.
 */
@Configuration
public class TermsSecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(PublicEndpoint.of(HttpMethod.GET, "/api/v1/terms/current"));
    }
}
