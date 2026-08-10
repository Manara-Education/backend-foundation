package com.manara.backend.auth.email;

import com.manara.backend.auth.model.OtpType;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.InlineImage;
import com.manara.backend.email.template.EmailImageLoader;
import com.manara.backend.email.template.EmailTemplateRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the factory against the real renderer, the real template and the real message bundles,
 * so a missing i18n key or a broken template placeholder fails here rather than in production.
 */
class OtpEmailFactoryTest {

    private OtpEmailFactory factory;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding("UTF-8");
        // Without this, an English lookup falls back to the *system* locale's bundle before the
        // base one — the English assertions below would fail on an Arabic-locale machine.
        messageSource.setFallbackToSystemLocale(false);

        factory = new OtpEmailFactory(new EmailTemplateRenderer(), new EmailImageLoader(),
                new MessageService(messageSource));
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void buildsAVerificationEmailContainingTheCode() {
        EmailMessage message = factory.create("student@manara.com", "123456",
                OtpType.EMAIL_VERIFICATION, 10);

        assertThat(message.to()).isEqualTo("student@manara.com");
        assertThat(message.subject()).isEqualTo("Your Manara verification code");
        assertThat(message.html())
                .contains("123456")
                .contains("Welcome to Manara")
                .contains("This code is valid for")
                .contains("10 minutes")
                .contains("never share your verification code with anyone")
                .doesNotContain("{{");
        assertThat(message.text()).contains("123456").contains("Welcome to Manara");
        assertThat(message.replyTo()).isNull();
    }

    @Test
    void usesPasswordResetCopyForResetCodes() {
        EmailMessage message = factory.create("student@manara.com", "654321",
                OtpType.PASSWORD_RESET, 10);

        assertThat(message.subject()).isEqualTo("Your Manara password reset code");
        assertThat(message.html()).contains("Reset your password").contains("654321");
    }

    @Test
    void rendersTheConfiguredExpiry() {
        EmailMessage message = factory.create("student@manara.com", "123456",
                OtpType.EMAIL_VERIFICATION, 15);

        assertThat(message.html()).contains("15 minutes");
    }

    /** The year is injected server-side — email clients do not execute JavaScript. */
    @Test
    void injectsTheCurrentYearIntoTheFooterWithoutGroupingSeparators() {
        String year = String.valueOf(java.time.Year.now().getValue());

        EmailMessage message = factory.create("student@manara.com", "123456",
                OtpType.EMAIL_VERIFICATION, 10);

        // Escaped to the &copy; entity by the renderer, which every email client renders as "©".
        // A grouped year would appear as "2,026", so this also pins the number formatting.
        assertThat(message.html()).contains("&copy; " + year + " Manara");
        assertThat(message.text()).contains("© " + year + " Manara");
    }

    @Test
    void followsTheRequestLocaleIncludingTextDirection() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        EmailMessage message = factory.create("student@manara.com", "123456",
                OtpType.EMAIL_VERIFICATION, 10);

        assertThat(message.html())
                .contains("lang=\"ar\"")
                .contains("dir=\"rtl\"")
                .contains("direction: rtl")
                .contains("text-align: right")
                .contains("مرحباً بك في منارة")
                .doesNotContain("{{");
        assertThat(message.subject()).isEqualTo("رمز التحقق الخاص بك في منارة");
    }

    /**
     * The brand mark is embedded rather than hot-linked, so it shows on first open instead of
     * waiting for the reader to allow remote images.
     */
    @Test
    void embedsTheManaraLogoAndReferencesItByContentId() {
        EmailMessage message = factory.create("student@manara.com", "123456",
                OtpType.EMAIL_VERIFICATION, 10);

        assertThat(message.inlineImages()).hasSize(1);
        InlineImage logo = message.inlineImages().getFirst();
        assertThat(logo.contentId()).isEqualTo("manara-logo");
        assertThat(logo.contentType()).isEqualTo("image/png");
        assertThat(logo.fileName()).isEqualTo("manara-logo.png");
        // Real PNG bytes, Base64-encoded: "iVBORw0KGgo" is the PNG magic number.
        assertThat(logo.base64Content()).startsWith("iVBORw0KGgo");

        assertThat(message.html())
                .contains("src=\"cid:" + logo.contentId() + "\"")
                .contains("alt=\"Manara\"");
    }

    /** The OTP must stay left-to-right and isolated, or RTL flow reorders the digits. */
    @Test
    void isolatesTheOtpFromRightToLeftFlow() {
        LocaleContextHolder.setLocale(Locale.of("ar"));

        String html = factory.create("student@manara.com", "123456",
                OtpType.EMAIL_VERIFICATION, 10).html();

        assertThat(html).contains("direction: ltr").contains("unicode-bidi: isolate");
        // Latin digits, exactly as the user must type them back.
        assertThat(html).contains(">123456<");
    }

    @Test
    void rejectsCodesThatAreNotSixDigits() {
        assertThatThrownBy(() -> factory.create("student@manara.com",
                "<img src=x onerror=alert(1)>", OtpType.EMAIL_VERIFICATION, 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> factory.create("student@manara.com", "12345",
                OtpType.EMAIL_VERIFICATION, 10))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> factory.create("student@manara.com", null,
                OtpType.EMAIL_VERIFICATION, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
