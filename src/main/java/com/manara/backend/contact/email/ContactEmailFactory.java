package com.manara.backend.contact.email;

import com.manara.backend.common.service.MessageService;
import com.manara.backend.contact.dto.ContactRequest;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.template.EmailTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Builds the internal notification a contact-form submission becomes: one plain operational
 * email to whoever reads {@link com.manara.backend.contact.config.ContactProperties#recipientEmail()},
 * with the visitor's own address as Reply-To so answering it is one click.
 *
 * <p>Every value that reaches the template goes through {@link EmailTemplateRenderer}, which
 * HTML-escapes each substitution — the name and message are visitor-supplied text landing in an
 * HTML email, and neither is trusted to be safe markup.
 */
@Component
@RequiredArgsConstructor
public class ContactEmailFactory {

    private static final String TEMPLATE_PATH = "templates/email/contact-message.html";
    private static final String RTL_LANGUAGE = "ar";

    private final EmailTemplateRenderer templateRenderer;
    private final MessageService messageService;

    public EmailMessage create(ContactRequest request, String recipient) {
        String subject = messageService.get("contact.email.subject", request.getTopic());
        boolean rightToLeft = RTL_LANGUAGE.equals(LocaleContextHolder.getLocale().getLanguage());

        String html = templateRenderer.render(TEMPLATE_PATH, Map.ofEntries(
                Map.entry("LANG", LocaleContextHolder.getLocale().getLanguage()),
                Map.entry("DIR", rightToLeft ? "rtl" : "ltr"),
                Map.entry("ALIGN", rightToLeft ? "right" : "left"),
                Map.entry("VALUE_ALIGN", rightToLeft ? "left" : "right"),
                Map.entry("SUBJECT", subject),
                Map.entry("TITLE", messageService.get("contact.email.title")),
                Map.entry("TOPIC_LABEL", messageService.get("contact.email.topicLabel")),
                Map.entry("NAME_LABEL", messageService.get("contact.email.nameLabel")),
                Map.entry("EMAIL_LABEL", messageService.get("contact.email.emailLabel")),
                Map.entry("MESSAGE_LABEL", messageService.get("contact.email.messageLabel")),
                Map.entry("TOPIC", request.getTopic()),
                Map.entry("NAME", request.getName()),
                Map.entry("EMAIL", request.getEmail()),
                Map.entry("MESSAGE", request.getMessage())));

        return EmailMessage.builder()
                .to(recipient)
                .subject(subject)
                .html(html)
                .replyTo(request.getEmail())
                .text(String.join("\n\n",
                        request.getTopic(),
                        request.getName() + " <" + request.getEmail() + ">",
                        request.getMessage()))
                .build();
    }
}
