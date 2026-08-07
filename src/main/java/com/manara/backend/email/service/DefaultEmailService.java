package com.manara.backend.email.service;

import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.email.provider.EmailProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Default {@link EmailService}: delegates to the configured provider and logs the attempt.
 *
 * <p>Deliberately thin. Cross-cutting delivery policy (retries, async dispatch, suppression lists)
 * would be layered in here without touching callers or the provider.
 *
 * <p>Logging never includes subject or body: transactional emails routinely carry OTPs, password
 * reset codes and magic links, so the body is treated as a secret. Recipients are masked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultEmailService implements EmailService {

    private final EmailProvider emailProvider;

    @Override
    public EmailSendResult send(EmailMessage message) {
        String recipient = maskRecipient(message.to());
        log.debug("Email send requested to={}", recipient);

        try {
            EmailSendResult result = emailProvider.send(message);
            log.info("Email accepted by provider to={} messageId={}", recipient, result.messageId());
            return result;
        } catch (RuntimeException ex) {
            log.warn("Email send failed to={}", recipient);
            throw ex;
        }
    }

    /**
     * Renders {@code student@manara.com} as {@code s***@manara.com} so logs stay useful for support
     * without turning into a mailing list.
     */
    private String maskRecipient(String address) {
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
