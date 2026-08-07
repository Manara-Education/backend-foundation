package com.manara.backend.email.provider;

import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;

/**
 * Infrastructure port for the concrete mail provider.
 *
 * <p>Implementations translate the generic {@link EmailMessage} into a provider request, apply the
 * configured sender, and map provider failures onto
 * {@link com.manara.backend.email.exception.EmailDeliveryException}. Provider SDK types must not
 * cross this boundary.
 *
 * <p>Swapping providers (SES, Postmark, …) means adding an implementation and changing wiring —
 * nothing above this interface changes.
 */
public interface EmailProvider {

    EmailSendResult send(EmailMessage message);
}
