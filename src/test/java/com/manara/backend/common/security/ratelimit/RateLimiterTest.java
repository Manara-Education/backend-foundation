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

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isTrue();
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isTrue();
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isTrue();
    }

    @Test
    void theRequestPastTheAllowanceIsRefused() {
        when(valueOps.increment(anyString())).thenReturn(4L);

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isFalse();
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

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isFalse();
        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.2")).isTrue();
    }

    /**
     * Redis is also the session store. If it is unreachable, authenticated traffic is already
     * failing; refusing unauthenticated traffic as well would turn a degraded dependency into a
     * total outage. The durable OTP attempt ceiling in PostgreSQL is unaffected either way.
     */
    @Test
    void anUnreachableRedisAllowsTheRequestRatherThanBlockingEveryone() {
        when(valueOps.increment(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThat(rateLimiter.tryConsume(RULE, "10.0.0.1")).isTrue();
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    private long currentWindow() {
        return System.currentTimeMillis() / 1000 / RULE.window().toSeconds();
    }
}
