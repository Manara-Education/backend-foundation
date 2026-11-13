package com.manara.backend.privacy.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * The privacy feature's slice of the security policy: one anonymous, read-only route.
 *
 * <p>A privacy policy has to be readable by someone deciding whether to create an account, so this
 * cannot sit behind authentication. It carries no personal data and nothing about who is asking.
 */
@Configuration
public class PrivacyPolicySecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(PublicEndpoint.of(HttpMethod.GET, "/api/v1/privacy-policy/current"));
    }
}
