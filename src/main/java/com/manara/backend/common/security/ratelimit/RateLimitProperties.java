package com.manara.backend.common.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.List;

/**
 * Which endpoints are rate limited, and how hard.
 *
 * <p>The defaults below are deliberately generous enough that a real person never meets them —
 * they exist to stop automation, not to police users. Every one can be tuned per environment
 * without a code change, and the whole filter can be switched off with {@code enabled=false}.
 *
 * @param enabled master switch.
 * @param rules   the rules. Evaluated in order; the FIRST match wins, so more specific patterns
 *                must come before broader ones.
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(boolean enabled, List<RateLimitRule> rules) {

    public RateLimitProperties {
        rules = (rules == null || rules.isEmpty()) ? defaults() : rules;
    }

    private static List<RateLimitRule> defaults() {
        return List.of(
                // --- Credential guessing -------------------------------------------------
                // Brute force and credential stuffing. A person who has genuinely forgotten
                // which password they used does not need more than ten tries in five minutes.
                new RateLimitRule("login", HttpMethod.POST, "/api/v1/auth/login", 10, Duration.ofMinutes(5)),

                // --- Codes that gate account takeover ------------------------------------
                // Backs up the per-code attempt ceiling in OtpService. That ceiling is the real
                // defence; this stops an attacker cheaply cycling fresh codes to get five new
                // guesses each time.
                new RateLimitRule("otp-verify", HttpMethod.POST, "/api/v1/auth/verify-otp", 10, Duration.ofMinutes(10)),
                new RateLimitRule("reset-otp-verify", HttpMethod.POST, "/api/v1/auth/verify-reset-otp", 10, Duration.ofMinutes(10)),
                new RateLimitRule("reset-password", HttpMethod.POST, "/api/v1/auth/reset-password", 10, Duration.ofMinutes(10)),

                // --- Endpoints that make US send email -----------------------------------
                // Each of these causes an outbound Resend message. Unthrottled they are a free
                // mail cannon pointed at any address an attacker chooses, billed to this
                // account and damaging to the sending domain's reputation.
                new RateLimitRule("register", HttpMethod.POST, "/api/v1/auth/register", 5, Duration.ofMinutes(15)),
                new RateLimitRule("resend-otp", HttpMethod.POST, "/api/v1/auth/resend-otp", 5, Duration.ofMinutes(15)),
                new RateLimitRule("forgot-password", HttpMethod.POST, "/api/v1/auth/forgot-password", 5, Duration.ofMinutes(15)),

                // --- Disk ----------------------------------------------------------------
                // 5 MB per file against a 38 GB disk shared with Postgres. Uploads are already
                // restricted to authenticated instructors, so this is a ceiling on damage from
                // one compromised account, not a gate on normal use.
                new RateLimitRule("upload", HttpMethod.POST, "/api/v1/uploads", 60, Duration.ofMinutes(10)));
    }
}
