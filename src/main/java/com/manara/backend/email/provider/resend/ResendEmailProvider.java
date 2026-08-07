package com.manara.backend.email.provider.resend;

import com.manara.backend.email.config.EmailProperties;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.email.provider.EmailProvider;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.Emails;
import com.resend.services.emails.model.Attachment;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resend implementation of the {@link EmailProvider} port.
 *
 * <p>Its entire job is translation: generic message in, Resend request out, provider result or
 * {@link EmailDeliveryException} back. It holds no knowledge of OTPs, password resets or any other
 * use case, and builds no email content — that belongs to the feature that owns the content.
 *
 * <p>Resend SDK types never escape this class.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResendEmailProvider implements EmailProvider {

    private final Emails emails;
    private final EmailProperties emailProperties;
    private final ResendProperties resendProperties;

    @Override
    public EmailSendResult send(EmailMessage message) {
        if (!resendProperties.hasApiKey()) {
            throw new EmailDeliveryException("error.email.notConfigured");
        }

        try {
            CreateEmailResponse response = emails.send(toCreateEmailOptions(message));
            return new EmailSendResult(response.getId());
        } catch (ResendException ex) {
            log.warn("Resend rejected the message: category={} status={} error={}",
                    categorize(ex.getStatusCode()), ex.getStatusCode(), ex.getErrorName());
            throw new EmailDeliveryException("error.email.deliveryFailed", ex);
        } catch (RuntimeException ex) {
            // The SDK wraps transport failures (connect/read timeout, DNS, TLS) in a bare
            // RuntimeException, so this is the network path rather than a defensive catch-all.
            log.warn("Resend request failed before a response was received: category=transport cause={}",
                    ex.getClass().getSimpleName());
            throw new EmailDeliveryException("error.email.deliveryFailed", ex);
        }
    }

    private CreateEmailOptions toCreateEmailOptions(EmailMessage message) {
        CreateEmailOptions.Builder builder = CreateEmailOptions.builder()
                .from(emailProperties.from().formatted())
                .to(message.to())
                .subject(message.subject())
                .html(message.html());

        if (hasText(message.text())) {
            builder.text(message.text());
        }

        String replyTo = hasText(message.replyTo()) ? message.replyTo() : emailProperties.replyTo();
        if (hasText(replyTo)) {
            builder.replyTo(replyTo);
        }

        if (!message.inlineImages().isEmpty()) {
            builder.attachments(message.inlineImages().stream()
                    .map(image -> Attachment.builder()
                            .fileName(image.fileName())
                            .contentId(image.contentId())
                            .contentType(image.contentType())
                            .content(image.base64Content())
                            .build())
                    .toList());
        }

        return builder.build();
    }

    /**
     * Classifies a provider failure for operators. Purely a logging aid — the exception thrown to
     * the application stays provider-independent, so callers cannot branch on Resend semantics.
     */
    private String categorize(Integer statusCode) {
        if (statusCode == null) {
            return "unknown";
        }
        return switch (statusCode) {
            case 401, 403 -> "provider-authentication";
            case 422 -> "invalid-request-or-sender-domain";
            case 429 -> "rate-limited";
            default -> statusCode >= 500 ? "provider-temporary-failure" : "invalid-request";
        };
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
