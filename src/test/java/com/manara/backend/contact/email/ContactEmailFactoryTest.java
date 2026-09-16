package com.manara.backend.contact.email;

import com.manara.backend.common.service.MessageService;
import com.manara.backend.contact.dto.ContactRequest;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.template.EmailTemplateRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against the real renderer and real message bundles, as {@code AccountExistsEmailFactoryTest}
 * is, so a missing key or placeholder fails here rather than in somebody's inbox.
 */
class ContactEmailFactoryTest {

    private ContactEmailFactory factory;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);

        factory = new ContactEmailFactory(new EmailTemplateRenderer(), new MessageService(messageSource));
        LocaleContextHolder.setLocale(Locale.of("ar"));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void buildsAMessageAddressedToTheConfiguredRecipientWithTheVisitorAsReplyTo() {
        ContactRequest request = ContactRequest.builder()
                .name("سارة أحمد")
                .email("sara@example.com")
                .topic("الدورات")
                .message("متى تبدأ الدورة القادمة؟")
                .build();

        EmailMessage message = factory.create(request, "support@manara-edu.com");

        assertThat(message.to()).isEqualTo("support@manara-edu.com");
        assertThat(message.replyTo()).isEqualTo("sara@example.com");
        assertThat(message.subject()).isEqualTo("[تواصل] الدورات");
        assertThat(message.html())
                .contains("سارة أحمد")
                .contains("متى تبدأ الدورة القادمة؟")
                .doesNotContain("{{");
    }

    @Test
    void escapesMarkupInTheVisitorSuppliedNameAndMessage() {
        ContactRequest request = ContactRequest.builder()
                .name("<script>alert(1)</script>")
                .email("attacker@example.com")
                .topic("مشكلة تقنية")
                .message("<img src=x onerror=alert(1)>")
                .build();

        EmailMessage message = factory.create(request, "support@manara-edu.com");

        assertThat(message.html())
                .doesNotContain("<script>")
                .doesNotContain("<img src=x onerror")
                .contains("&lt;script&gt;");
    }
}
