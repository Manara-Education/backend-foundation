package com.manara.backend.course.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * The course feature's slice of the unauthenticated surface: the public catalogue, and nothing
 * else.
 *
 * <p>Two exact {@code GET} routes rather than a wildcard. A pattern such as
 * {@code /api/v1/public/**} would make whatever is mapped under it later public without anybody
 * deciding so; naming each route means a new anonymous endpoint is a change to this list, reviewed
 * as one. Every other method on these paths — and every learner, enrolment and checkout route —
 * still falls through to the chain's terminal {@code anyRequest().authenticated()}.
 *
 * <p>Contributed here, next to the routes it opens, as {@code TermsSecurityConfig} does for the
 * terms version. {@code AuthSecurityConfig#authorizeHttpRequestsCustomizer} collects it.
 */
@Configuration
public class PublicCourseSecurityConfig implements PublicEndpointContribution {

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(
                PublicEndpoint.of(HttpMethod.GET, "/api/v1/public/courses"),
                PublicEndpoint.of(HttpMethod.GET, "/api/v1/public/courses/{courseId}"));
    }
}
