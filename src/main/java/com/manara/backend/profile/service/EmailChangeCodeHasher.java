package com.manara.backend.profile.service;

import com.manara.backend.profile.config.EmailChangeProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Turns a six-digit code into the value stored for it, and checks a submitted code against that.
 *
 * <p>An HMAC-SHA256 over the request id, the code's generation and the code, keyed by a server
 * secret. Without the key a stored value cannot be tested offline against the million possible
 * codes, which an unkeyed hash of six digits could.
 */
@Slf4j
@Component
public class EmailChangeCodeHasher {

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public EmailChangeCodeHasher(EmailChangeProperties properties, SecureRandom secureRandom) {
        byte[] secret;
        if (properties.codeSecret() == null || properties.codeSecret().isBlank()) {
            secret = new byte[32];
            secureRandom.nextBytes(secret);
            log.warn("app.email-change.code-secret is not set; using a key generated for this run. "
                    + "Email-change codes pending at a restart will stop working. Set EMAIL_CHANGE_CODE_SECRET.");
        } else {
            secret = properties.codeSecret().getBytes(StandardCharsets.UTF_8);
        }
        this.key = new SecretKeySpec(secret, ALGORITHM);
    }

    public String hash(UUID requestId, int generation, String code) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal((requestId + ":" + generation + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }

    /** Constant-time, so how much of a guess was right cannot be timed. */
    public boolean matches(UUID requestId, int generation, String code, String storedHash) {
        if (code == null || storedHash == null) return false;
        byte[] expected = storedHash.getBytes(StandardCharsets.US_ASCII);
        byte[] actual = hash(requestId, generation, code).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, actual);
    }
}
