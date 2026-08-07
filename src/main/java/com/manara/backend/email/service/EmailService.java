package com.manara.backend.email.service;

import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;

/**
 * Application-facing entry point for sending email.
 *
 * <p>Intentionally transport-only: there is exactly one method and it knows nothing about OTP,
 * password resets or any other use case. Features build their own {@link EmailMessage} (typically
 * via a feature-owned factory) and hand it over. Adding a new kind of email requires no change here
 * and no change to any provider.
 */
public interface EmailService {

    /**
     * Submits a message to the configured mail provider.
     *
     * @return the provider-accepted message identifier
     * @throws com.manara.backend.email.exception.EmailDeliveryException if the provider rejected the
     *                                                                   message or was unreachable
     */
    EmailSendResult send(EmailMessage message);
}
