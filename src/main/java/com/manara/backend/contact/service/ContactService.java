package com.manara.backend.contact.service;

import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.contact.config.ContactProperties;
import com.manara.backend.contact.dto.ContactRequest;
import com.manara.backend.contact.dto.ContactTopic;
import com.manara.backend.contact.email.ContactEmailFactory;
import com.manara.backend.email.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * What a contact-form submission does: become one email, sent while the caller is still
 * waiting.
 *
 * <p>Deliberately synchronous — unlike {@code DeferredEmailDispatcher}, which exists to hide a
 * failure from the caller for anti-enumeration reasons on auth flows. Nothing here needs hiding:
 * a visitor who is told their message went through has to be right, so a provider failure must
 * reach them as a real failure rather than a success that quietly never arrives.
 * {@link com.manara.backend.email.exception.EmailDeliveryException} is left to propagate; the
 * existing {@code GlobalExceptionHandler} maps it to {@code 503} with a generic message.
 */
@Service
@RequiredArgsConstructor
public class ContactService {

    private final ContactEmailFactory contactEmailFactory;
    private final EmailService emailService;
    private final ContactProperties contactProperties;
    private final MessageService messageService;

    public MessageResponse submit(ContactRequest request) {
        if (!ContactTopic.DISPLAY_VALUES.contains(request.getTopic())) {
            throw new BusinessException("validation.contact.topic.invalid");
        }

        String recipient = contactProperties.recipientEmail();
        if (recipient == null || recipient.isBlank()) {
            throw new BusinessException("error.contact.notConfigured");
        }

        emailService.send(contactEmailFactory.create(request, recipient));

        return MessageResponse.builder()
                .message(messageService.get("contact.success.message"))
                .build();
    }
}
