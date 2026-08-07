package com.manara.backend.email.provider.resend;

import com.resend.Resend;
import com.resend.services.emails.Emails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Owns the Resend client's lifecycle.
 *
 * <p>{@code Resend#emails()} builds a fresh {@code Emails} service on every call, and each one
 * constructs its own OkHttp client with its own connection pool and dispatcher threads. Resolving
 * the service once here and injecting it means the application keeps a single HTTP client instead
 * of leaking one per outgoing email.
 */
@Slf4j
@Configuration
public class ResendConfiguration {

    @Bean
    public Resend resend(ResendProperties properties) {
        if (!properties.hasApiKey()) {
            log.warn("RESEND_API_KEY is not set — the application will start, but every email send "
                    + "will fail until the variable is configured.");
        }
        return new Resend(properties.apiKey());
    }

    @Bean
    public Emails resendEmails(Resend resend) {
        return resend.emails();
    }
}
