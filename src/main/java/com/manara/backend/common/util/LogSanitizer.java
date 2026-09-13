package com.manara.backend.common.util;

/**
 * Makes a value this application did not choose safe to write into a log line.
 *
 * <p>A log is only evidence if every line in it was written by this application. A value carrying a
 * line break — a request path, a submitted form field, an exception message quoting a remote
 * server's response — can end the real entry early and start a forged one after it that reads
 * exactly like the application wrote it (CWE-117). Anything a caller did not originate goes through
 * {@link #sanitize} before it reaches a logger.
 *
 * <p>Line breaks become {@code _}, so the entry stays on one line and it is still visible that
 * something was there. {@code \R} rather than {@code [\r\n]}, because it also matches the breaks a
 * log viewer honours that those two miss: NEL and the Unicode line and paragraph separators. Every
 * other control character is replaced too — an ANSI escape sequence in a log line is an instruction
 * to whichever terminal ends up displaying it.
 */
public final class LogSanitizer {

    private LogSanitizer() {
        throw new AssertionError("Utility class");
    }

    /**
     * @param value anything that did not originate in this codebase, or {@code null}
     * @return the value on one line with no control characters, or {@code null} if it was {@code null}
     */
    public static String sanitize(String value) {
        if (value == null) {
            return null;
        }
        // String#replaceAll with literal patterns rather than precompiled ones, deliberately: this is
        // the form CodeQL's log-injection query recognises as removing line breaks, and it only ever
        // runs on warning paths.
        return value.replaceAll("\\R", "_").replaceAll("\\p{Cc}", "_");
    }
}
