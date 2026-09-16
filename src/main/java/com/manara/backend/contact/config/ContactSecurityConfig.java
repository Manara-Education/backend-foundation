package com.manara.backend.contact.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * The contact feature's slice of the unauthenticated surface: one route, the same way
 * {@code PublicCourseSecurityConfig} and {@code TermsSecurityConfig} contribute theirs.
 *
 * <p>A visitor reaching the contact form has, by definition, no account to sign in with yet —
 * that is the whole reason the form exists — so this sits alongside registration and login as a
 * route the security chain must let through with no session.
 */
@Configuration
public class ContactSecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(PublicEndpoint.of(HttpMethod.POST, "/api/v1/contact"));
    }
}
