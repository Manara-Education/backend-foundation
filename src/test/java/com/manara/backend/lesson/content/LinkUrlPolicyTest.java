package com.manara.backend.lesson.content;

import com.manara.backend.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The allowlist, from both sides.
 *
 * <p>The refusals are the point, but the acceptances matter too: a policy that refused ordinary
 * links would be safe and useless, and an instructor who cannot link to a worksheet will paste the
 * address as text where nobody can click it.
 */
class LinkUrlPolicyTest {

    private final LinkUrlPolicy policy = new LinkUrlPolicy();

    @ParameterizedTest(name = "accepts {0}")
    @ValueSource(strings = {
            "https://example.com",
            "https://example.com/path?query=1&other=2#fragment",
            "http://example.com",
            "https://ar.wikipedia.org/wiki/تفكير_نقدي",
            "mailto:tutor@example.com",
            "tel:+201234567890"})
    void acceptsOrdinaryLinks(String url) {
        assertThat(policy.requireSafe(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("accepts a long link, which is what a worksheet URL usually is")
    void acceptsALongLink() {
        String url = "https://example.com/" + "segment/".repeat(40) + "worksheet.pdf";
        assertThat(policy.requireSafe(url)).isEqualTo(url);
    }

    @Test
    @DisplayName("returns the address as typed rather than a normalised form of it")
    void doesNotRewriteTheAddress() {
        // A policy that returned URI's canonical form would show the instructor back a link they
        // did not write, and would change a stored document for no reason a learner can see.
        String url = "https://Example.COM/Path";
        assertThat(policy.requireSafe(url)).isEqualTo(url);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(policy.requireSafe("  https://example.com  ")).isEqualTo("https://example.com");
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==",
            "vbscript:msgbox(1)",
            "blob:https://example.com/uuid",
            "filesystem:https://example.com/temporary/x",
            "file:///etc/passwd",
            "ftp://example.com/x"})
    void refusesEverythingOffTheAllowlist(String url) {
        // Refusal is the assertion. Which refusal it is varies — a payload carrying characters that
        // are not legal in a URI at all fails the parse before the scheme is reached — and pinning
        // the code here would be testing the order of two checks rather than the policy.
        assertThatThrownBy(() -> policy.requireSafe(url)).isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest(name = "names the reason for a well-formed {0}")
    @ValueSource(strings = {
            "javascript:alert(1)", "vbscript:msgbox(1)", "ftp://example.com/x", "file:///etc/passwd"})
    void namesTheSchemeAsTheReasonWhenTheAddressItselfParses(String url) {
        // An instructor who pasted an FTP link needs to hear that the scheme is the problem, not
        // that their address is malformed — it is not.
        assertThatThrownBy(() -> policy.requireSafe(url))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkSchemeUnsupported");
    }

    @Test
    @DisplayName("a scheme split by a control character is still the scheme it is")
    void refusesControlCharacterObfuscation() {
        // Browsers have historically been willing to read past these; a check that ran on the raw
        // string would be validating a different value from the one that gets followed.
        assertThatThrownBy(() -> policy.requireSafe("java	script:alert(1)"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireSafe("java\nscript:alert(1)"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireSafe("‮javascript:alert(1)"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("a relative address is refused rather than resolved against whatever page renders it")
    void refusesRelativeAddresses() {
        assertThatThrownBy(() -> policy.requireSafe("/courses/1/lessons/2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkSchemeRequired");
        assertThatThrownBy(() -> policy.requireSafe("example.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkSchemeRequired");
    }

    @Test
    void refusesAWebLinkWithNoHost() {
        assertThatThrownBy(() -> policy.requireSafe("https:///nowhere"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkMalformed");
    }

    @Test
    void refusesBlankAndOverlongAddresses() {
        assertThatThrownBy(() -> policy.requireSafe("   "))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkRequired");
        assertThatThrownBy(() -> policy.requireSafe("https://example.com/" + "x".repeat(3000)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.linkTooLong");
    }

    @Test
    void isSafeAnswersWithoutRaising() {
        assertThat(policy.isSafe("https://example.com")).isTrue();
        assertThat(policy.isSafe("javascript:alert(1)")).isFalse();
        assertThat(policy.isSafe(null)).isFalse();
    }
}
