package com.manara.backend.common.security.ratelimit;

import java.util.HashMap;
import java.util.Map;

/**
 * Fixed-window counts kept in this process, for the rules that go on limiting while Redis cannot.
 *
 * <p>Bounded on purpose. During an outage every distinct client key is a new entry, and an unbounded
 * map is a way to exhaust the heap by rotating source addresses. Entries whose window has closed are
 * evicted when room is needed; when every entry is still live, a key that is not yet tracked is
 * refused rather than admitted uncounted, so filling the map never becomes a way around the limit.
 *
 * <p>Per instance. With two instances each counts its own traffic, so the effective limit during an
 * outage is the configured one times the number of instances. That is accepted: it is a bound for the
 * minutes Redis is away, not the normal mechanism.
 */
final class LocalFallbackLimiter {

    private final int maxTrackedKeys;

    /** Key → {window end in epoch millis, requests counted}. Guarded by {@code this}. */
    private final Map<String, long[]> windows = new HashMap<>();

    LocalFallbackLimiter(int maxTrackedKeys) {
        if (maxTrackedKeys <= 0) {
            throw new IllegalArgumentException("maxTrackedKeys must be positive");
        }
        this.maxTrackedKeys = maxTrackedKeys;
    }

    /**
     * Counts one request for {@code key}.
     *
     * @return true while the window's count is within {@code limit}; false past it, and false when
     *         the key is new and there is no room left to count it in
     */
    synchronized boolean tryConsume(String key, int limit, long windowEndsAtMillis, long nowMillis) {
        long[] window = windows.get(key);
        if (window == null || window[0] <= nowMillis) {
            if (window == null && windows.size() >= maxTrackedKeys) {
                windows.values().removeIf(entry -> entry[0] <= nowMillis);
                if (windows.size() >= maxTrackedKeys) {
                    return false;
                }
            }
            window = new long[] {windowEndsAtMillis, 0};
            windows.put(key, window);
        }
        return ++window[1] <= limit;
    }

    synchronized int trackedKeys() {
        return windows.size();
    }
}
