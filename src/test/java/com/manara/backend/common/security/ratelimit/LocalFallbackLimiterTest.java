package com.manara.backend.common.security.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalFallbackLimiterTest {

    @Test
    void countsUpToTheLimitWithinAWindowAndStartsAgainInTheNext() {
        var limiter = new LocalFallbackLimiter(10);
        assertThat(limiter.tryConsume("a:1", 2, 1_000, 0)).isTrue();
        assertThat(limiter.tryConsume("a:1", 2, 1_000, 10)).isTrue();
        assertThat(limiter.tryConsume("a:1", 2, 1_000, 20)).isFalse();
        // The next window is a different key, and the closed one no longer counts.
        assertThat(limiter.tryConsume("a:2", 2, 2_000, 1_000)).isTrue();
    }

    /**
     * Never more entries than the bound, and a full map is not a way around the limit: a key that
     * cannot be tracked is refused, not let through uncounted.
     */
    @Test
    void holdsAtMostItsBoundAndRefusesAnUntrackableKey() {
        var limiter = new LocalFallbackLimiter(3);
        for (String key : new String[] {"a", "b", "c"}) {
            assertThat(limiter.tryConsume(key, 5, 1_000, 0)).isTrue();
        }
        assertThat(limiter.tryConsume("d", 5, 1_000, 10)).as("no room while every window is live").isFalse();
        assertThat(limiter.trackedKeys()).isEqualTo(3);
    }

    @Test
    void closedWindowsAreEvictedToMakeRoom() {
        var limiter = new LocalFallbackLimiter(2);
        assertThat(limiter.tryConsume("a", 5, 100, 0)).isTrue();
        assertThat(limiter.tryConsume("b", 5, 100, 0)).isTrue();
        assertThat(limiter.tryConsume("c", 5, 300, 200)).as("a and b closed at 100").isTrue();
        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
