package com.manara.backend.banner.service;

import com.manara.backend.banner.dto.BannerRequest;
import com.manara.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The rules the management form applies while somebody types, applied again to whatever arrives.
 *
 * <p>The call-to-action ones are the reason this is not left to the form: the button is a link an
 * instructor puts in front of every learner on the platform, so what may be in it is decided here.
 */
class BannerValidatorTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 22, 9, 0);

    private final BannerValidator bannerValidator = new BannerValidator();

    @Test
    void aButtonLabelWithNoDestinationIsRefused() {
        assertThatThrownBy(() -> bannerValidator.validate(request()
                .callToActionLabel("سجّل الآن")
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.ctaUrlRequired");
    }

    /** A destination with no label is never rendered, so it is left alone rather than rejected. */
    @Test
    void aDestinationWithNoLabelIsAccepted() {
        assertThatCode(() -> bannerValidator.validate(request()
                .callToActionUrl("https://manara.com/courses/9")
                .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void anAbsoluteLinkAnInAppLinkAndAnAnchorAreAllAccepted() {
        assertThatCode(() -> {
            bannerValidator.validate(request().callToActionLabel("a").callToActionUrl("https://manara.com/x").build());
            bannerValidator.validate(request().callToActionLabel("a").callToActionUrl("http://manara.com/x").build());
            bannerValidator.validate(request().callToActionLabel("a").callToActionUrl("/main?view=explore").build());
            bannerValidator.validate(request().callToActionLabel("a").callToActionUrl("#course-9").build());
        }).doesNotThrowAnyException();
    }

    /** The attack this closes: a script handed to every learner from a control they are asked to press. */
    @Test
    void aScriptDestinationIsRefused() {
        assertThatThrownBy(() -> bannerValidator.validate(request()
                .callToActionLabel("اضغط هنا")
                .callToActionUrl("javascript:alert(document.cookie)")
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.ctaUrlInvalid");
    }

    @Test
    void aDataUrlDestinationIsRefused() {
        assertThatThrownBy(() -> bannerValidator.validate(request()
                .callToActionLabel("اضغط هنا")
                .callToActionUrl("data:text/html;base64,PHNjcmlwdD4=")
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.ctaUrlInvalid");
    }

    @Test
    void aBannerThatIsGoingToBeDeliveredMustSayWhenItStops() {
        assertThatThrownBy(() -> bannerValidator.validate(BannerRequest.builder()
                .internalName("internal")
                .title("title")
                .startAt(START)
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.endRequired");
    }

    /** Half-scheduled is exactly what a draft is for. */
    @Test
    void aDraftMayBeSavedWithNoEnd() {
        assertThatCode(() -> bannerValidator.validate(BannerRequest.builder()
                .internalName("internal")
                .title("title")
                .draft(true)
                .build()))
                .doesNotThrowAnyException();
    }

    @Test
    void anEndBeforeItsStartIsRefused() {
        assertThatThrownBy(() -> bannerValidator.validate(BannerRequest.builder()
                .internalName("internal")
                .title("title")
                .startAt(START)
                .endAt(START.minusHours(1))
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.endBeforeStart");
    }

    @Test
    void anEndEqualToItsStartIsRefused() {
        assertThatThrownBy(() -> bannerValidator.validate(BannerRequest.builder()
                .internalName("internal")
                .title("title")
                .startAt(START)
                .endAt(START)
                .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.endBeforeStart");
    }

    @Test
    void aPriorityBelowOneIsRefused() {
        assertThatThrownBy(() -> bannerValidator.validate(request().priority(0).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.priorityPositive");
    }

    /** No position sent is the normal case — the form has no field for one. */
    @Test
    void anAbsentPriorityIsAccepted() {
        assertThatCode(() -> bannerValidator.validate(request().build())).doesNotThrowAnyException();
    }

    private BannerRequest.BannerRequestBuilder request() {
        return BannerRequest.builder()
                .internalName("internal")
                .title("title")
                .startAt(START)
                .endAt(START.plusDays(7));
    }
}
