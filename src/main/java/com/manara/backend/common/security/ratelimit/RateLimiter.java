package com.manara.backend.common.security.ratelimit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p><b>When Redis cannot be reached</b> this used to allow every request, so an outage switched
 * every limit off at once — sign-in included, where it meant unthrottled password checks. Each rule
 * now says what happens instead ({@link RateLimitRule.OutagePolicy}): sign-in is refused, because it
 * stores its session in Redis and cannot succeed anyway; everything else goes on being limited by a
 * bounded count held in this process ({@link LocalFallbackLimiter}). Failing every rule closed was
 * rejected: it would turn a Redis blip into refusing password recovery, which works without Redis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final String KEY_PREFIX = "manara:ratelimit:";

    /**
     * How many client keys the in-process fallback tracks at most. Every key is one entry for one
     * window, so this bounds the memory an outage can cost at well under a megabyte.
     */
    static final int MAX_FALLBACK_KEYS = 10_000;

    /** During an outage every request fails the same way; one line a minute says so. */
    private static final long OUTAGE_REPORT_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    /** What the filter does with a request. */
    public enum Decision {
        /** Within the allowance. */
        ALLOWED,
        /** Past the allowance: 429. */
        LIMITED,
        /** The count cannot be kept and the rule refuses rather than guess: 503. */
        UNAVAILABLE
    }

    private final StringRedisTemplate redis;

    private final LocalFallbackLimiter fallback = new LocalFallbackLimiter(MAX_FALLBACK_KEYS);

    private final AtomicLong lastOutageReport = new AtomicLong(Long.MIN_VALUE / 2);

    /**
     * Registers one request against {@code rule} for {@code clientKey}.
     */
    public Decision tryConsume(RateLimitRule rule, String clientKey) {
        // The window number is part of the key, so windows roll over on their own and no
        // sweeping or expiry bookkeeping is needed beyond the TTL below.
        long windowSeconds = Math.max(1, rule.window().toSeconds());
        long nowMillis = System.currentTimeMillis();
        long windowNumber = nowMillis / 1000 / windowSeconds;
        String key = KEY_PREFIX + rule.name() + ":" + clientKey + ":" + windowNumber;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count == null) {
                return Decision.ALLOWED;
            }
            if (count == 1L) {
                // Only on first use in this window. Re-setting it on every request would push the
                // expiry forward indefinitely and the key would never fall out of Redis.
                redis.expire(key, Duration.ofSeconds(windowSeconds));
            }
            return count <= rule.limit() ? Decision.ALLOWED : Decision.LIMITED;
        } catch (RuntimeException ex) {
            reportOutage(rule, ex);
            if (rule.onRedisOutage() == RateLimitRule.OutagePolicy.REFUSE) {
                return Decision.UNAVAILABLE;
            }
            long windowEndsAtMillis = (windowNumber + 1) * windowSeconds * 1000;
            return fallback.tryConsume(key, rule.limit(), windowEndsAtMillis, nowMillis)
                    ? Decision.ALLOWED
                    : Decision.LIMITED;
        }
    }

    /** Names the rule, never the client key or anything from the request body. */
    private void reportOutage(RateLimitRule rule, RuntimeException ex) {
        long now = System.currentTimeMillis();
        long last = lastOutageReport.get();
        if (now - last >= OUTAGE_REPORT_INTERVAL_MILLIS && lastOutageReport.compareAndSet(last, now)) {
            log.warn("Rate limiting cannot reach Redis (rule '{}'); sign-in is refused and other rules are "
                    + "limited per instance until it returns. Repeats of this are logged at most once a minute.",
                    rule.name(), ex);
        }
    }

    int fallbackTrackedKeys() {
        return fallback.trackedKeys();
    }
}
