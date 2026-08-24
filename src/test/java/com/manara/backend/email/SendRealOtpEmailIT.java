package com.manara.backend.email;

import com.manara.backend.auth.email.OtpEmailFactory;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.config.EmailProperties;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.email.provider.resend.ResendEmailProvider;
import com.manara.backend.email.provider.resend.ResendProperties;
import com.manara.backend.email.service.DefaultEmailService;
import com.manara.backend.email.service.EmailService;
import com.manara.backend.email.template.EmailImageLoader;
import com.manara.backend.email.template.EmailTemplateRenderer;
import com.resend.Resend;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Manual runner: sends one real OTP email through the production path.
 * Not matched by Surefire's default includes; run explicitly with -Dtest.
 */
class SendRealOtpEmailIT {

    @Test
    void send() throws Exception {
        String recipient = System.getProperty("to");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));

        String apiKey = env.getProperty("resend.api-key");
        String from = System.getProperty("mailFrom", env.getProperty("email.from.address"));
        String fromName = env.getProperty("email.from.name");
        String replyTo = env.getProperty("email.reply-to");

        System.out.println("[send] to=" + recipient + " from=" + fromName + " <" + from + ">");

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        OtpEmailFactory factory = new OtpEmailFactory(
                new EmailTemplateRenderer(), new EmailImageLoader(), new MessageService(messages));
        EmailService emailService = new DefaultEmailService(new ResendEmailProvider(
                new Resend(apiKey).emails(),
                new EmailProperties(new EmailProperties.Sender(from, fromName), replyTo),
                new ResendProperties(apiKey)));

        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));

        LocaleContextHolder.setLocale(Locale.of("ar"));
        EmailMessage message = factory.create(recipient, code, OtpType.EMAIL_VERIFICATION, 10);
        LocaleContextHolder.resetLocaleContext();

        System.out.println("[send] inlineImages=" + message.inlineImages().size()
                + " logoBytes=" + message.inlineImages().getFirst().base64Content().length());

        EmailSendResult result = emailService.send(message);
        System.out.println("[send] ACCEPTED messageId=" + result.messageId());
    }
}
