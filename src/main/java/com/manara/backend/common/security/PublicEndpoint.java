package com.manara.backend.common.security;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

public record PublicEndpoint(HttpMethod method, String pattern) {

    public static PublicEndpoint of(HttpMethod method, String pattern) {
        return new PublicEndpoint(method, pattern);
    }

    public RequestMatcher toMatcher() {
        var builder = PathPatternRequestMatcher.withDefaults();
        return method == null
                ? builder.matcher(pattern)
                : builder.matcher(method, pattern);
    }
}
