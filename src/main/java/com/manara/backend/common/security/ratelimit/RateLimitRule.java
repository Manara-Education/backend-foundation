package com.manara.backend.common.security.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.util.pattern.PathPatternParser;

import java.time.Duration;

/**
 * One rate-limiting rule: how many requests to a given endpoint one client may make in a window.
 *
 * @param name    short identifier, used in the Redis key and in logs. Must be stable — changing it
 *                resets every counter currently in flight.
 * @param method  HTTP method the rule applies to, or {@code null} for any.
 * @param pattern path pattern, in the same syntax the security configuration uses.
 * @param limit   requests permitted per window, per client.
 * @param window  length of the window.
 */
public record RateLimitRule(String name, HttpMethod method, String pattern, int limit, Duration window) {

    public RateLimitRule {
        if (limit <= 0) {
            throw new IllegalArgumentException("Rate limit for '" + name + "' must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Rate limit window for '" + name + "' must be positive");
        }
    }

    public RequestMatcher toMatcher() {
        var builder = PathPatternRequestMatcher.withPathPatternParser(PathPatternParser.defaultInstance);
        return method == null ? builder.matcher(pattern) : builder.matcher(method, pattern);
    }
}
