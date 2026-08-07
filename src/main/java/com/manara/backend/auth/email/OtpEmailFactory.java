package com.manara.backend.auth.email;

import com.manara.backend.auth.model.OtpType;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.template.EmailImageLoader;
import com.manara.backend.email.template.EmailTemplateRenderer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the OTP email. Content belongs to the OTP feature; delivery belongs to the email feature.
 *
 * <p>This lives beside the feature rather than in {@code auth/mapper} on purpose: mappers in this
 * codebase build entities and response DTOs and are kept pure, whereas this collaborator renders a
 * template and resolves localized copy. Adding a new kind of email means adding a factory like this
 * one — the email feature and the Resend provider stay untouched.
 */
@Component
@RequiredArgsConstructor
public class OtpEmailFactory {

    private static final String TEMPLATE_PATH = "templates/email/otp-code.html";
    private static final String LOGO_PATH = "templates/email/manara-logo.png";
    private static final String LOGO_CONTENT_ID = "manara-logo";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    private static final String RTL_LANGUAGE = "ar";

    private final EmailTemplateRenderer templateRenderer;
    private final EmailImageLoader imageLoader;
    private final MessageService messageService;

    /**
     * @param expirationMinutes passed in rather than read from configuration here, so the lifetime
     *                          shown to the user is the same value the OTP service actually applied
     */
    public EmailMessage create(String recipient, String code, OtpType type, int expirationMinutes) {
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            throw new IllegalArgumentException("OTP code must be exactly six digits");
        }

        String prefix = messagePrefix(type);
        String title = messageService.get(prefix + ".title");
        String intro = messageService.get(prefix + ".intro");
        String expiryLead = messageService.get("email.otp.expiry.lead");
        // Passed as text, not a number: MessageFormat would otherwise apply locale grouping and
        // locale-specific digits, turning 2026 into "2,026" and diverging from the approved design.
        String expiryValue = messageService.get("email.otp.expiry.value", String.valueOf(expirationMinutes));
        String securityLead = messageService.get("email.otp.security.lead");
        String securityStrong = messageService.get("email.otp.security.strong");
        String securityTail = messageService.get("email.otp.security.tail");
        String disclaimer = messageService.get("email.otp.disclaimer");
        String signoff = messageService.get("email.otp.signoff");
        String team = messageService.get("email.otp.team");
        String copyright = messageService.get("email.otp.copyright", String.valueOf(Year.now().getValue()));

        boolean rightToLeft = RTL_LANGUAGE.equals(LocaleContextHolder.getLocale().getLanguage());
        String html = templateRenderer.render(TEMPLATE_PATH, Map.ofEntries(
                Map.entry("LANG", LocaleContextHolder.getLocale().getLanguage()),
                Map.entry("DIR", rightToLeft ? "rtl" : "ltr"),
                Map.entry("ALIGN", rightToLeft ? "right" : "left"),
                Map.entry("BRAND_NAME", messageService.get("email.brand.name")),
                Map.entry("LOGO_ALT", messageService.get("email.brand.logoAlt")),
                Map.entry("LOGO_CID", LOGO_CONTENT_ID),
                Map.entry("TITLE", title),
                Map.entry("INTRO", intro),
                Map.entry("OTP", code),
                Map.entry("EXPIRY_LEAD", expiryLead),
                Map.entry("EXPIRY_VALUE", expiryValue),
                Map.entry("SECURITY_LEAD", securityLead),
                Map.entry("SECURITY_STRONG", securityStrong),
                Map.entry("SECURITY_TAIL", securityTail),
                Map.entry("DISCLAIMER", disclaimer),
                Map.entry("SIGNOFF", signoff),
                Map.entry("TEAM", team),
                Map.entry("COPYRIGHT", copyright)));

        return EmailMessage.builder()
                .to(recipient)
                .subject(messageService.get(prefix + ".subject"))
                .html(html)
                .inlineImages(List.of(imageLoader.load(LOGO_PATH, LOGO_CONTENT_ID, "image/png")))
                .text(String.join("\n\n",
                        title,
                        intro,
                        code,
                        expiryLead + " " + expiryValue,
                        securityLead + " " + securityStrong + securityTail,
                        disclaimer,
                        signoff + "\n" + team,
                        copyright))
                .build();
    }

    private String messagePrefix(OtpType type) {
        return switch (type) {
            case EMAIL_VERIFICATION -> "email.otp.verification";
            case PASSWORD_RESET -> "email.otp.reset";
        };
    }
}
