package com.manara.backend.profile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The email-change lifetime policy.
 *
 * <ul>
 *   <li>A code is valid for {@code codeValidity} (10 minutes) from when it was sent.</li>
 *   <li>A new code may be requested after {@code resendCooldown} (60 seconds), at most
 *       {@code maxResends} times per request; each resend invalidates the previous code and never
 *       extends the request beyond {@code maxLifetime} from its creation.</li>
 *   <li>Wrong codes are counted per request, across resends; at {@code maxAttempts} the request is
 *       locked and a new one must be started, which is itself subject to the cooldown.</li>
 * </ul>
 *
 * @param codeSecret HMAC key for stored codes. When blank a random key is generated at start-up,
 *                   which only means codes pending at a restart stop working; set
 *                   {@code EMAIL_CHANGE_CODE_SECRET} in deployments that should survive restarts.
 */
@ConfigurationProperties(prefix = "app.email-change")
public record EmailChangeProperties(
        String codeSecret,
        Duration codeValidity,
        Duration resendCooldown,
        Duration maxLifetime,
        int maxAttempts,
        int maxResends) {

    public EmailChangeProperties {
        codeValidity = codeValidity == null ? Duration.ofMinutes(10) : codeValidity;
        resendCooldown = resendCooldown == null ? Duration.ofSeconds(60) : resendCooldown;
        maxLifetime = maxLifetime == null ? Duration.ofMinutes(30) : maxLifetime;
        maxAttempts = maxAttempts <= 0 ? 5 : maxAttempts;
        maxResends = maxResends <= 0 ? 3 : maxResends;
    }
}
