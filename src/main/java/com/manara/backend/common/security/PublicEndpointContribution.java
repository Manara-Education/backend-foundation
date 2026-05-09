package com.manara.backend.common.security;

import java.util.List;

/**
 * Each feature module that wants to expose unauthenticated endpoints implements this and
 * registers the implementation as a Spring bean. SecurityConfig collects every contribution
 * and applies them to the filter chain — this keeps feature isolation intact (auth doesn't
 * know what course exposes, course doesn't know what auth exposes).
 */
public interface PublicEndpointContribution {

    List<PublicEndpoint> endpoints();
}
