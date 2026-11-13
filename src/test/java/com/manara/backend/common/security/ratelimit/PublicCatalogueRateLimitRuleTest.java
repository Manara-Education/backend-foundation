package com.manara.backend.common.security.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The anonymous catalogue is throttled, and only the catalogue.
 *
 * <p>Asserted on the default rule set, which is what production runs unless an environment
 * overrides it: a rule that exists but does not match the routes it was written for would look
 * right in a diff and throttle nothing.
 */
class PublicCatalogueRateLimitRuleTest {

    private final RateLimitRule rule = new RateLimitProperties(true, null).rules().stream()
            .filter(candidate -> candidate.name().equals("public-catalogue"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no public-catalogue rule in the defaults"));

    private boolean matches(String method, String path) {
        return rule.toMatcher().matches(new MockHttpServletRequest(method, path));
    }

    @Test
    @DisplayName("covers the list and the detail route")
    void coversBothRoutes() {
        assertThat(matches("GET", "/api/v1/public/courses")).isTrue();
        assertThat(matches("GET", "/api/v1/public/courses/42")).isTrue();
    }

    @Test
    @DisplayName("touches nothing else")
    void touchesNothingElse() {
        assertThat(matches("GET", "/api/v1/student/courses")).isFalse();
        assertThat(matches("GET", "/api/v1/terms/current")).isFalse();
        assertThat(matches("POST", "/api/v1/public/courses")).isFalse();
    }

    @Test
    @DisplayName("allows far more than a person browsing, per client, per minute")
    void isGenerousToPeople() {
        assertThat(rule.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(rule.limit()).isEqualTo(300);
        assertThat(rule.onRedisOutage()).isEqualTo(RateLimitRule.OutagePolicy.LOCAL_LIMIT);
    }
}
