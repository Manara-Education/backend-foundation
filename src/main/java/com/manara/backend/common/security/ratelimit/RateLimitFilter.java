package com.manara.backend.common.security.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rejects requests that exceed the configured allowance for the endpoint they target.
 *
 * <p>Runs before authentication, because the endpoints that most need protecting — login,
 * registration, OTP verification, password reset — are exactly the ones an unauthenticated
 * caller reaches.
 */
@Slf4j
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration OUTAGE_RETRY_AFTER = Duration.ofSeconds(30);

    private final RateLimiter rateLimiter;
    private final Map<RequestMatcher, RateLimitRule> rules = new LinkedHashMap<>();

    public RateLimitFilter(RateLimiter rateLimiter, List<RateLimitRule> configuredRules) {
        this.rateLimiter = rateLimiter;
        // LinkedHashMap: rules are evaluated in declaration order and the first match wins, so a
        // specific pattern placed before a broad one keeps its own limit.
        configuredRules.forEach(rule -> rules.put(rule.toMatcher(), rule));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        RateLimitRule rule = matchingRule(request);
        if (rule == null) {
            filterChain.doFilter(request, response);
            return;
        }

        switch (rateLimiter.tryConsume(rule, clientKey(request))) {
            case ALLOWED -> filterChain.doFilter(request, response);
            case LIMITED -> {
                log.warn("Rate limit '{}' exceeded for {} {}", rule.name(), request.getMethod(), request.getRequestURI());
                reject(response, rule);
            }
            // Already logged, at most once a minute, by the limiter.
            case UNAVAILABLE -> unavailable(response);
        }
    }

    private RateLimitRule matchingRule(HttpServletRequest request) {
        for (var entry : rules.entrySet()) {
            if (entry.getKey().matches(request)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * Identifies the client by IP address.
     *
     * <p>Uses {@code getRemoteAddr()} and deliberately does NOT parse {@code X-Forwarded-For}
     * here. In production {@code server.forward-headers-strategy=native} has Tomcat's RemoteIp
     * valve replace the remote address with the real client IP from the proxy's headers, having
     * first validated the request actually came from the trusted proxy. Reading the header
     * directly in this filter would trust a value any client can set, and the entire limiter
     * could then be bypassed by sending a random {@code X-Forwarded-For} with every request.
     */
    private String clientKey(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    private void reject(HttpServletResponse response, RateLimitRule rule) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(rule.window().toSeconds()));
        // Deliberately says nothing about which rule fired, what the limit is, or how much of it
        // is left — that would tell an attacker exactly how to pace themselves underneath it.
        response.getWriter().write("{\"status\":\"error\",\"errors\":[\"Too many requests. Please try again later.\"]}");
    }

    /**
     * For a rule that refuses while its counter is down (RateLimitRule.OutagePolicy.REFUSE). Answered
     * before the request reaches authentication, so an outage neither lets passwords be checked
     * without a limit nor tells a right one from a wrong one.
     */
    private void unavailable(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(OUTAGE_RETRY_AFTER.toSeconds()));
        response.getWriter().write("{\"status\":\"error\",\"errors\":[\"This is temporarily unavailable. Please try again shortly.\"]}");
    }
}
