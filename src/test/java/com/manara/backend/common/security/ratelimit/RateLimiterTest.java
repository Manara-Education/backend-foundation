package com.manara.backend.common.security.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;

import java.time.Duration;

import com.manara.backend.common.security.ratelimit.RateLimiter.Decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimiterTest {

    private static final RateLimitRule RULE =
            new RateLimitRule("login", HttpMethod.POST, "/api/v1/auth/login", 3, Duration.ofMinutes(5));

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @InjectMocks
    private RateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void requestsWithinTheAllowanceArePermitted() {
        when(valueOps.increment(anyString())).thenReturn(1L, 2L, 3L);

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.ALLOWED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.ALLOWED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.ALLOWED);
    }

    @Test
    void theRequestPastTheAllowanceIsRefused() {
        when(valueOps.increment(anyString())).thenReturn(4L);

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.LIMITED);
    }

    /**
     * The expiry must be set once, when the window opens. Re-setting it on every request would
     * push it forward for as long as traffic continues, and the key would never fall out of Redis.
     */
    @Test
    void theWindowExpiryIsSetOnlyOnTheFirstRequestOfTheWindow() {
        when(valueOps.increment(anyString())).thenReturn(1L);
        rateLimiter.tryConsume(RULE, "10.0.0.1");
        verify(redis, times(1)).expire(anyString(), any(Duration.class));

        when(valueOps.increment(anyString())).thenReturn(2L);
        rateLimiter.tryConsume(RULE, "10.0.0.1");
        verify(redis, times(1)).expire(anyString(), any(Duration.class));
    }

    @Test
    void separateClientsDoNotShareAnAllowance() {
        when(valueOps.increment("manara:ratelimit:login:10.0.0.1:" + currentWindow())).thenReturn(4L);
        when(valueOps.increment("manara:ratelimit:login:10.0.0.2:" + currentWindow())).thenReturn(1L);

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.LIMITED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.2")).isEqualTo(Decision.ALLOWED);
    }

    /**
     * An outage used to allow every request, so it switched every limit off at once. A rule that
     * limits locally goes on counting, in this process, with the same allowance.
     */
    @Test
    void duringAnOutageALocallyLimitedRuleKeepsCounting() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.ALLOWED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.ALLOWED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.ALLOWED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isEqualTo(Decision.LIMITED);
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.2"))
                .as("another client keeps its own allowance")
                .isEqualTo(Decision.ALLOWED);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    /** A rule that refuses during an outage never lets a request through while Redis is away. */
    @Test
    void duringAnOutageARefusingRuleIsUnavailable() {
        var signIn = new RateLimitRule("login", HttpMethod.POST, "/api/v1/auth/login", 3, Duration.ofMinutes(5),
                RateLimitRule.OutagePolicy.REFUSE);
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("connection refused"));

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryConsume(signIn, "10.0.0.1")).isEqualTo(Decision.UNAVAILABLE);
        }
        assertThat(rateLimiter.fallbackTrackedKeys()).as("nothing is counted for a refusing rule").isZero();
    }

    @Test
    void theDefaultRulesRefuseSignInAndLimitEverythingElseLocally() {
        var rules = new RateLimitProperties(true, null).rules();

        assertThat(rules)
                .filteredOn(rule -> rule.onRedisOutage() == RateLimitRule.OutagePolicy.REFUSE)
                .extracting(RateLimitRule::name)
                .containsExactlyInAnyOrder("login", "otp-verify");
        assertThat(rules).extracting(RateLimitRule::onRedisOutage).doesNotContainNull();
    }

    private long currentWindow() {
        return System.currentTimeMillis() / 1000 / RULE.window().toSeconds();
    }
}
