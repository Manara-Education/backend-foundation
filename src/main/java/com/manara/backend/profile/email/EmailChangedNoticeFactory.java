package com.manara.backend.profile.email;

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
 * Tells an account's previous address that the account moved to a new one.
 *
 * <p>Sent only after the change has committed. It names neither the new address nor any code, and
 * says what to do if the change was not the owner's. Uses the informational layout the account-exists
 * notice already has.
 */
@Component
@RequiredArgsConstructor
public class EmailChangedNoticeFactory {

    private static final String TEMPLATE_PATH = "templates/email/account-exists.html";
    private static final String LOGO_PATH = "templates/email/manara-logo.png";
    private static final String LOGO_CONTENT_ID = "manara-logo";
    private static final String RTL_LANGUAGE = "ar";

    private final EmailTemplateRenderer templateRenderer;
    private final EmailImageLoader imageLoader;
    private final MessageService messageService;

    public EmailMessage create(String previousAddress) {
        String title = messageService.get("profile.email.changedNotice.title");
        String intro = messageService.get("profile.email.changedNotice.intro");
        String signIn = messageService.get("profile.email.changedNotice.signIn");
        String help = messageService.get("profile.email.changedNotice.help");
        String disclaimer = messageService.get("profile.email.changedNotice.disclaimer");
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
                Map.entry("UNVERIFIED", help),
                Map.entry("DISCLAIMER", disclaimer),
                Map.entry("SIGNOFF", signoff),
                Map.entry("TEAM", team),
                Map.entry("COPYRIGHT", copyright)));

        return EmailMessage.builder()
                .to(previousAddress)
                .subject(messageService.get("profile.email.changedNotice.subject"))
                .html(html)
                .inlineImages(List.of(imageLoader.load(LOGO_PATH, LOGO_CONTENT_ID, "image/png")))
                .text(String.join("\n\n", title, intro, signIn, help, disclaimer, signoff + "\n" + team, copyright))
                .build();
    }
}
