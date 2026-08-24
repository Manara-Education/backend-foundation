package com.manara.backend.common.security.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Counts requests per client per window, in Redis.
 *
 * <p>Redis rather than an in-process map because the counter must outlive a container restart —
 * otherwise every deployment hands an attacker a clean slate — and must stay correct if a second
 * instance is ever added. Redis is already a hard dependency here: it holds every session.
 *
 * <p>A fixed window, not a sliding one. The known trade-off is that a client can spend its whole
 * allowance at the end of one window and again at the start of the next, so the true worst case
 * is twice the configured limit over a window boundary. That is accepted deliberately: the limits
 * here are chosen to stop automation by orders of magnitude, and a factor of two does not change
 * whether enumerating a million OTP codes is feasible. The implementation is two Redis commands
 * with nothing to keep in memory, which a sliding window would not be.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String KEY_PREFIX = "manara:ratelimit:";

    private final StringRedisTemplate redis;

    /**
     * Registers one request against {@code rule} for {@code clientKey}.
     *
     * @return true if the request is within the allowance and may proceed
     */
    public boolean tryConsume(RateLimitRule rule, String clientKey) {
        // The window number is part of the key, so windows roll over on their own and no
        // sweeping or expiry bookkeeping is needed beyond the TTL below.
        long windowSeconds = Math.max(1, rule.window().toSeconds());
        long windowNumber = System.currentTimeMillis() / 1000 / windowSeconds;
        String key = KEY_PREFIX + rule.name() + ":" + clientKey + ":" + windowNumber;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                // Only on first use in this window. Re-setting it on every request would push the
                // expiry forward indefinitely and the key would never fall out of Redis.
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
            return count <= rule.limit();
        } catch (RuntimeException ex) {
            // Fail OPEN, and this is a deliberate choice worth stating.
            //
            // Redis is also the session store, so if it is unreachable then authenticated
            // requests are already failing; refusing unauthenticated ones too would convert a
            // degraded Redis into a total outage. Rate limiting is a mitigation, not the
            // authentication boundary — the per-code OTP attempt ceiling, which lives in
            // PostgreSQL, keeps working regardless.
            log.warn("Rate limit check failed for rule '{}'; allowing the request", rule.name(), ex);
            return true;
        }
    }
}
