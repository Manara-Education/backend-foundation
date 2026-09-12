package com.manara.backend.auth.email;

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

/**
 * Builds the account-exists notice: what registration emails to an address that already has an
 * account, in place of the "already registered" error it used to show whoever was registering.
 *
 * <p>It carries no code, no token and no link, only what the owner can do from the ordinary screens.
 * The notice is sent because somebody typed the address. Anything secret in it would be handed out
 * to whoever that was, as often as they cared to ask.
 *
 * <p>Built the way {@link OtpEmailFactory} is, with localized copy from the message bundles, the
 * shared renderer and the brand's inline logo, so the two emails read as one family.
 */
@Component
@RequiredArgsConstructor
public class AccountExistsEmailFactory {

    private static final String TEMPLATE_PATH = "templates/email/account-exists.html";
    private static final String LOGO_PATH = "templates/email/manara-logo.png";
    private static final String LOGO_CONTENT_ID = "manara-logo";
    private static final String RTL_LANGUAGE = "ar";

    private final EmailTemplateRenderer templateRenderer;
    private final EmailImageLoader imageLoader;
    private final MessageService messageService;

    public EmailMessage create(String recipient) {
        String title = messageService.get("auth.email.accountExists.title");
        String intro = messageService.get("auth.email.accountExists.intro");
        String signIn = messageService.get("auth.email.accountExists.signIn");
        String unverified = messageService.get("auth.email.accountExists.unverified");
        String disclaimer = messageService.get("auth.email.accountExists.disclaimer");
        // The sign-off is the OTP email's, word for word: same sender, same voice.
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
                Map.entry("SIGN_IN", signIn),
                Map.entry("UNVERIFIED", unverified),
                Map.entry("DISCLAIMER", disclaimer),
                Map.entry("SIGNOFF", signoff),
                Map.entry("TEAM", team),
                Map.entry("COPYRIGHT", copyright)));

        return EmailMessage.builder()
                .to(recipient)
                .subject(messageService.get("auth.email.accountExists.subject"))
                .html(html)
                .inlineImages(List.of(imageLoader.load(LOGO_PATH, LOGO_CONTENT_ID, "image/png")))
                .text(String.join("\n\n",
                        title,
                        intro,
                        signIn,
                        unverified,
                        disclaimer,
                        signoff + "\n" + team,
                        copyright))
                .build();
    }
}
