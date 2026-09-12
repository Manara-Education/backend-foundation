package com.manara.backend.session.manager;

import com.manara.backend.session.config.SessionCeilingProperties;
import com.manara.backend.session.config.SessionConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Holds every account to at most {@link SessionCeilingProperties#maximumPerAccount()} sessions, ending
 * the oldest when a new one would go past it.
 *
 * <p>The ceiling used to be a {@code maximumSessions(5)} line in the security configuration. That
 * configures Spring Security's concurrency control, which is only ever invoked by Spring Security's
 * own authentication filters — and sign-in here is a service call that establishes the session
 * itself, so the control never ran and an account could hold as many sessions as it signed in for.
 * Its register was an in-memory map besides, which across several instances would have been a
 * separate ceiling per instance even had it run.
 *
 * <p>So the register lives in Redis, beside the sessions it counts: one sorted set per account
 * ({@link #indexKey}), whose members are session ids scored by when each was admitted. Everything
 * that can take the set past the ceiling happens inside the single script below, and Redis runs a
 * script to completion before any other command from any client. Two sign-ins racing each other, on
 * one instance or on several, are therefore counted one after the other, never both against the same
 * stale total.
 *
 * <p><b>Oldest</b> means admitted longest ago by Redis's own clock ({@code TIME}), the one clock all
 * instances share. A new score is never allowed to fall behind the newest already in the set, so the
 * order is strictly the order of admission even if that clock steps backwards — and the session being
 * admitted is always the newest, so whatever is evicted, it is never the session that caused it.
 *
 * <p><b>Counted once stored, not once created.</b> With {@code flush-mode=on_save} a new session is
 * not written to Redis until its response is committed. Counting it earlier would let a concurrent
 * sign-in evict a session whose key did not exist yet: the delete would find nothing, the session
 * would be written a moment later, and it would be live and uncounted — the very bypass being closed.
 * {@link HttpSessionManager} therefore only records the session, and {@link SessionAdmissionFilter}
 * admits it once Spring Session has stored it. Because every member was in the store when it was
 * added, a member whose key has since gone is a session that has ended — signed out, replaced,
 * revoked or expired — and the script drops it before counting, with no grace period to tune.
 *
 * <p><b>Evicted sessions are deleted by the script</b>, in the same step that takes them out of the
 * set; deleting afterwards from Java would leave a window (a crash, a dropped connection) in which a
 * session had left the register and was still in the store. The delete is a {@code DEL} of the
 * session's key, which is all {@code RedisSessionRepository#deleteById} does. That key format is
 * pinned by {@code SessionCeilingTest}, so a change to it fails a test instead of quietly switching
 * the ceiling off.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionCeiling {

    private static final String PENDING_ADMISSION_ATTRIBUTE = SessionCeiling.class.getName() + ".PENDING_ADMISSION";

    private static final String INDEX_KEY_PREFIX = "manara:account-sessions:";

    /** What {@code RedisSessionRepository} puts in front of a session id to make its key. */
    private static final String SESSION_KEY_PREFIX = SessionConfig.REDIS_NAMESPACE + ":sessions:";

    /**
     * How long an account's register outlives its latest admission.
     *
     * <p>This exists so that an account which stops signing in does not leave its set behind forever,
     * and it is not a bound on a session: sliding expiry keeps a session in regular use alive
     * indefinitely. If every session on an account stays in use for this long with no sign-in in
     * between, the register expires beneath them and they stop being counted until they end. Thirty
     * days makes that a month of continuous use without one sign-in.
     */
    private static final Duration INDEX_TIME_TO_LIVE = Duration.ofDays(30);

    private static final String ADMITTED = "ADMITTED";

    /**
     * KEYS[1] the account's register; ARGV: the session being admitted, the ceiling, the session-key
     * prefix, the register's time to live in seconds. Replies {@code ADMITTED} followed by the ids it
     * evicted, or {@code ABSENT} if the session being admitted is not in the store.
     */
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT = RedisScript.of("""
            local index, admitted, ceiling, prefix = KEYS[1], ARGV[1], tonumber(ARGV[2]), ARGV[3]

            -- A session whose write failed is not counted: the request that opened it failed too.
            if redis.call('EXISTS', prefix .. admitted) == 0 then
              return {'ABSENT'}
            end

            -- Members whose sessions have ended since they were admitted hold no slot.
            for _, id in ipairs(redis.call('ZRANGE', index, 0, -1)) do
              if redis.call('EXISTS', prefix .. id) == 0 then
                redis.call('ZREM', index, id)
              end
            end

            -- Milliseconds on the Redis clock, kept strictly ahead of the newest member.
            local clock = redis.call('TIME')
            local score = tonumber(clock[1]) * 1000 + math.floor(tonumber(clock[2]) / 1000)
            local newest = redis.call('ZRANGE', index, -1, -1, 'WITHSCORES')
            if newest[2] and tonumber(newest[2]) >= score then
              score = tonumber(newest[2]) + 1
            end
            redis.call('ZADD', index, score, admitted)

            local reply = {'ADMITTED'}
            local excess = redis.call('ZCARD', index) - ceiling
            if excess > 0 then
              for _, id in ipairs(redis.call('ZRANGE', index, 0, excess - 1)) do
                redis.call('DEL', prefix .. id)
                table.insert(reply, id)
              end
              redis.call('ZREMRANGEBYRANK', index, 0, excess - 1)
            end

            redis.call('EXPIRE', index, ARGV[4])
            return reply
            """, List.class);

    private final StringRedisTemplate redis;
    private final SessionCeilingProperties properties;

    /** The sorted set registering {@code accountId}'s sessions. */
    public static String indexKey(long accountId) {
        return INDEX_KEY_PREFIX + accountId;
    }

    /** The key Spring Session stores {@code sessionId} under. */
    public static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    /**
     * Records that this request has established a session for {@code accountId}, to be admitted once
     * stored. Read after the id has been rotated, so it is the id the response's cookie will carry.
     */
    void deferAdmission(HttpServletRequest request, long accountId) {
        request.setAttribute(PENDING_ADMISSION_ATTRIBUTE,
                new PendingAdmission(accountId, request.getSession().getId()));
    }

    /** Admits the session this request established, if it established one. At most once per request. */
    void admitPending(HttpServletRequest request) {
        if (!(request.getAttribute(PENDING_ADMISSION_ATTRIBUTE) instanceof PendingAdmission pending)) {
            return;
        }
        request.removeAttribute(PENDING_ADMISSION_ATTRIBUTE);
        admit(pending.accountId(), pending.sessionId());
    }

    /**
     * Gives back the slot a session held, when it is ended on purpose: signing out, or being torn down
     * for a stale authentication epoch.
     *
     * <p>Best effort. A slot not given back here is reclaimed on the account's next sign-in, because
     * the script drops members whose sessions no longer exist before it counts — so failing a sign-out
     * over bookkeeping that corrects itself would be the wrong trade.
     */
    void release(long accountId, String sessionId) {
        try {
            redis.opsForZSet().remove(indexKey(accountId), sessionId);
        } catch (RuntimeException ex) {
            log.warn("Could not release a session slot for account {}; its next sign-in reclaims it", accountId, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private void admit(long accountId, String sessionId) {
        List<String> reply;
        try {
            reply = redis.execute(ADMIT, List.of(indexKey(accountId)), sessionId,
                    String.valueOf(properties.maximumPerAccount()), SESSION_KEY_PREFIX,
                    String.valueOf(INDEX_TIME_TO_LIVE.toSeconds()));
        } catch (RuntimeException ex) {
            // Fail closed. A session that cannot be counted is ended rather than left running outside
            // the ceiling, which would be exactly the uncounted session this class exists to prevent.
            // Redis is the session store as well, so this only happens while sessions themselves are
            // failing, and it costs the user one more sign-in once it recovers.
            log.error("Could not count a new session against account {}'s ceiling; ending it", accountId, ex);
            endUncounted(sessionId);
            return;
        }

        if (reply == null || !ADMITTED.equals(reply.getFirst())) {
            log.warn("A session established for account {} was not in the store when it came to be counted",
                    accountId);
            return;
        }
        int evicted = reply.size() - 1;
        if (evicted > 0) {
            log.info("Account {} went past its ceiling of {} sessions; ended the {} oldest",
                    accountId, properties.maximumPerAccount(), evicted);
        }
    }

    private void endUncounted(String sessionId) {
        try {
            redis.delete(sessionKey(sessionId));
        } catch (RuntimeException ex) {
            log.error("Could not end the uncounted session either; it remains usable until it expires", ex);
        }
    }

    private record PendingAdmission(long accountId, String sessionId) {
    }
}
