package com.manara.backend.auth.service;

import com.manara.backend.auth.email.AccountExistsEmailFactory;
import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.email.service.DeferredEmailDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Tells the owner of an address that somebody tried to register it, at most once an hour.
 *
 * <p>This is what registration does instead of answering "that address is taken". Whoever typed the
 * address gets the same reply as anybody registering a new one. Only the owner is told what happened,
 * in their own mailbox, with what to do about it.
 *
 * <p>The throttle exists because this sends mail to one person on the say-so of anybody else. Without
 * it the registration form would be a way to fill a stranger's inbox, limited only by the per-IP rate
 * limit, which somebody with many addresses to send from is not limited by. One notice an hour is
 * enough for the owner to find out; more would tell them nothing new.
 *
 * <p>The throttle lives in Redis under a SHA-256 of the canonical address rather than the address, so
 * the keyspace is not a readable list of who has an account. If Redis cannot be reached the notice is
 * skipped rather than sent unthrottled. A missed notice costs the owner a message they seldom need;
 * an unthrottled one would be the flood this class exists to stop. The registration is answered the
 * same way whichever happens.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountExistsNotifier {

    private static final String KEY_PREFIX = "manara:auth:account-exists-notice:";
    private static final Duration INTERVAL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final AccountExistsEmailFactory emailFactory;
    private final DeferredEmailDispatcher deferredEmailDispatcher;

    /**
     * Queues the notice for after the current transaction commits, unless this address was sent one
     * within the hour. Reports nothing either way, for the same reason the dispatcher does not.
     */
    public void notifyOwner(String email) {
        if (claimThisWindow(EmailAddress.canonical(email))) {
            deferredEmailDispatcher.dispatchAfterCommit(emailFactory.create(email));
        }
    }

    private boolean claimThisWindow(String canonicalEmail) {
        try {
            // SET NX with an expiry, as one command: only the first caller in the window gets true,
            // and the key falls out of Redis on its own when the window closes.
            return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(KEY_PREFIX + sha256(canonicalEmail), "1", INTERVAL));
        } catch (RuntimeException ex) {
            // No address in this line: it would put the very membership fact this avoids into logs.
            log.warn("Account-exists notice throttle unavailable; notice skipped", ex);
            return false;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform is required to provide SHA-256", e);
        }
    }
}
