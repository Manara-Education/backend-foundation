package com.manara.backend.common.util;

import java.util.Locale;

/**
 * The one definition of what an email address means to this application.
 *
 * <p>An account is identified by its address, and an address identifies exactly one account. That
 * only holds if every layer agrees on which strings are "the same address" — so the rule lives
 * here, once, and everything that touches an address calls this rather than restating it:
 *
 * <ul>
 *   <li>{@code CanonicalEmailDeserializer} applies it to every inbound request field, so a padded
 *       or shouted address is already canonical before Bean Validation looks at it;</li>
 *   <li>{@code AuthMapper} applies it when building a {@code User}, and {@code User#setEmail} when
 *       changing one, so the column only ever holds the canonical form;</li>
 *   <li>{@code UserRepository} applies it to every lookup argument, so a caller that got its
 *       string from somewhere other than a request still finds the right row.</li>
 * </ul>
 *
 * <p>{@link Locale#ROOT} is not decoration. {@code "ALI@X.COM".toLowerCase()} on a JVM whose
 * default locale is Turkish yields {@code "alı@x.com"} — dotless ı — which is a different string
 * from {@code "ali@x.com"}. The account a user could sign in to would then depend on the server's
 * locale. {@code Locale.ROOT} makes the mapping locale-independent.
 *
 * <p>Nothing beyond trimming and lower-casing is done. Stripping dots or {@code +tags} from the
 * local part is a provider-specific convention, not a property of email, and applying it would
 * merge addresses that genuinely belong to different people.
 */
public final class EmailAddress {

    private EmailAddress() {
        throw new AssertionError("Utility class");
    }

    /**
     * Reduces an address to the single form this application stores and compares.
     *
     * @param email raw address as supplied by a client, or {@code null}
     * @return the canonical form, or {@code null} if {@code email} was {@code null}
     */
    public static String canonical(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
