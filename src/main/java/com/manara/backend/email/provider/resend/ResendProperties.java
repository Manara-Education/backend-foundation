package com.manara.backend.email.provider.resend;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resend credentials, supplied exclusively through the {@code RESEND_API_KEY} environment variable.
 *
 * <p>The key is never defaulted to a literal and never logged.
 */
@ConfigurationProperties(prefix = "resend")
public record ResendProperties(String apiKey) {

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
