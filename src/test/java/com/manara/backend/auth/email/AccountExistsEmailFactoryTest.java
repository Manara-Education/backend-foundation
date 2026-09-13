package com.manara.backend.auth.email;

import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.template.EmailImageLoader;
import com.manara.backend.email.template.EmailTemplateRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against the real renderer, template and bundles, as {@link OtpEmailFactoryTest} is, so a missing
 * key or placeholder fails here and not in somebody's inbox.
 */
class AccountExistsEmailFactoryTest {

    private AccountExistsEmailFactory factory;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setFallbackToSystemLocale(false);

        factory = new AccountExistsEmailFactory(new EmailTemplateRenderer(), new EmailImageLoader(),
                new MessageService(messageSource));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void tellsTheOwnerWhatToDoAndCarriesNothingSecret() {
        EmailMessage message = factory.create("owner@manara.com");

        assertThat(message.to()).isEqualTo("owner@manara.com");
        assertThat(message.subject()).isEqualTo("Someone tried to create a Manara account with your email");
        assertThat(message.html())
                .contains("You already have a Manara account")
                .contains("sign in with your existing password")
                .contains("request a new code from the verification page")
                .contains("safely ignore this email")
                .doesNotContain("{{");
        // Guidance only: whoever typed the address asked for this email to be sent.
        assertThat(message.text())
                .contains("Forgot password")
                .doesNotContainPattern("\\d{6}")
                .doesNotContain("http");
    }

    @Test
    void rendersRightToLeftInArabic() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        EmailMessage message = factory.create("owner@manara.com");

        assertThat(message.html()).contains("dir=\"rtl\"").contains("لديك حساب في منارة بالفعل");
        assertThat(message.subject()).isEqualTo("محاولة لإنشاء حساب في منارة ببريدك الإلكتروني");
    }
}
