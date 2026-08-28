package com.manara.backend.lesson.content;

import com.manara.backend.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Which addresses instructor-authored content is allowed to point at.
 *
 * <p>Every link and every call-to-action in a lesson passes through here on the way into the
 * database. That is the point: the editor checks the same rule so an instructor is told immediately,
 * but the editor is a convenience and this is the boundary. A payload posted straight at the API
 * with {@code javascript:alert(1)} in it never becomes a row.
 *
 * <h2>Allowlist, not denylist</h2>
 * The unsafe schemes are famously not a closed set — {@code javascript}, {@code data},
 * {@code vbscript}, {@code blob}, {@code filesystem}, and whatever a browser ships next — so this
 * names the four that are allowed and refuses the rest by construction. A denylist would have to be
 * revised every time a browser grows a new way to execute a URL; this does not.
 *
 * <h2>Why the parse is not the whole check</h2>
 * {@code java.net.URI} will happily parse {@code javascript:alert(1)} — it is a syntactically valid
 * opaque URI whose scheme is {@code javascript}. Parsing therefore proves nothing on its own, and
 * the scheme test below is what actually decides. Leading control characters and whitespace are
 * stripped first, because {@code "java\nscript:alert(1)"} is a string browsers have historically
 * been willing to read past.
 */
@Component
public class LinkUrlPolicy {

    /**
     * The only schemes a lesson may link to.
     *
     * <p>{@code mailto} and {@code tel} are here deliberately rather than incidentally: a lesson
     * pointing at a tutor's address or a support line is ordinary educational content, and both are
     * inert — they hand the address to the platform's own handler and cannot execute in the page.
     */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https", "mailto", "tel");

    /** Long enough for any real link; short enough that a row cannot be used as storage. */
    private static final int MAX_LENGTH = 2048;

    /**
     * Returns the address to store, or explains why there is not one.
     *
     * @throws BusinessException when the value is blank, unparseable, over length, relative, or on
     *                           a scheme that is not allowed
     */
    public String requireSafe(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException("error.richContent.linkRequired");
        }

        // Stripped before anything looks at the scheme. A browser asked to follow
        // "java\tscript:alert(1)" has historically been willing to ignore the tab; a check that ran
        // on the raw string would be reading a different value from the one that gets executed.
        String url = stripControlCharacters(rawUrl).trim();
        if (url.isEmpty()) {
            throw new BusinessException("error.richContent.linkRequired");
        }
        if (url.length() > MAX_LENGTH) {
            throw new BusinessException("error.richContent.linkTooLong");
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BusinessException("error.richContent.linkMalformed");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            // A relative address. Refused rather than resolved: what it would resolve against is
            // whichever Manara page happens to be rendering the lesson, which is not a decision the
            // author made and not one that stays the same between surfaces.
            throw new BusinessException("error.richContent.linkSchemeRequired");
        }
        if (!ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("error.richContent.linkSchemeUnsupported");
        }

        // An http(s) URL with no host is "https:///foo" and similar — parseable, on an allowed
        // scheme, and pointing nowhere.
        if (isWebScheme(scheme) && (uri.getHost() == null || uri.getHost().isBlank())) {
            throw new BusinessException("error.richContent.linkMalformed");
        }

        return url;
    }

    /** Whether this address may be stored, without raising when it may not. */
    public boolean isSafe(String rawUrl) {
        try {
            requireSafe(rawUrl);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private boolean isWebScheme(String scheme) {
        String lower = scheme.toLowerCase(Locale.ROOT);
        return lower.equals("http") || lower.equals("https");
    }

    /**
     * Removes the characters that let a hostile string read as one thing here and another in a
     * browser: C0 and C1 controls, and the zero-width and bidi-override characters that can hide a
     * scheme inside what looks like ordinary text.
     */
    private String stripControlCharacters(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            boolean control = codePoint <= 0x1F
                    || (codePoint >= 0x7F && codePoint <= 0x9F)
                    || codePoint == 0x200B || codePoint == 0x200C || codePoint == 0x200D
                    || codePoint == 0xFEFF
                    || (codePoint >= 0x202A && codePoint <= 0x202E)
                    || (codePoint >= 0x2066 && codePoint <= 0x2069);
            if (!control) {
                cleaned.appendCodePoint(codePoint);
            }
        });
        return cleaned.toString();
    }
}
