package com.manara.backend.auth.password;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What a new password has to be. The one definition every password-setting path uses —
 * registration, change and reset — through {@link ValidPassword}, {@link PasswordNotPersonal} and
 * {@code AuthService#changePassword}.
 *
 * <p>Four requirements a person is shown and ticks off while typing: at least 15 characters, an
 * upper-case English letter, a digit and a symbol. The client's checklist
 * ({@code src/features/auth/password-policy} in frontend-foundation) tests the same four with the
 * same patterns, in the same order, and words each refusal as {@code messages_ar.properties} does.
 * The client's copy saves a round trip; this one is the rule, and applies to a request sent
 * straight to the API just the same.
 *
 * <p>Behind them sit the checks no checklist can show: a ceiling, a list of passwords known to be
 * common or breached, and a few context-specific refusals. Their messages say what to change, never
 * how the password is stored.
 *
 * <p>Applied only when a password is <em>set</em>. Sign-in never consults it, so accounts created
 * under an earlier rule keep working. Nothing here changes what is hashed either: the normalised
 * form below exists for comparison only.
 */
public final class PasswordPolicy {

    /** Counted in code points, so an emoji or any character outside the BMP is one, not two. */
    public static final int MIN_CODE_POINTS = 15;

    /**
     * bcrypt's own limit. It uses at most 72 bytes of the UTF-8 encoding, and the Spring Security
     * encoder refuses anything longer outright — which reached the client as a 500. Refused here
     * first and never truncated: two passwords sharing their first 72 bytes would otherwise be the
     * same password. The refusal only says the password is too long; bytes are this class's
     * concern, not the reader's.
     */
    public static final int MAX_UTF8_BYTES = 72;

    /** English capitals only. Arabic has no letter case, so an upper-case letter can only mean A to Z. */
    private static final Pattern UPPERCASE = Pattern.compile("[A-Z]");

    private static final Pattern NUMBER = Pattern.compile("[0-9]");

    /**
     * Any character Unicode classes as punctuation or a symbol, in any script — {@code !}, {@code ؟},
     * {@code €}, an emoji. A list of allowed symbols would refuse perfectly good ones. JavaScript's
     * {@code /[\p{P}\p{S}]/u} puts every code point in the same class, which is what lets the client
     * apply this rule without ever disagreeing with the server.
     */
    private static final Pattern SYMBOL = Pattern.compile("[\\p{P}\\p{S}]");

    static final String BUNDLED_BLOCKLIST = "/auth/password-blocklist.txt";

    /** Both spellings people use for the Arabic name: ending in taa marbuta, and in haa. */
    private static final List<String> SERVICE_NAMES = List.of("manara", "منارة", "مناره");

    /** "abababab…", "12341234…": a unit this short repeated to length is not a passphrase. */
    private static final int LONGEST_REPEATED_UNIT = 4;

    /**
     * Shorter identity terms are matched only exactly. Searching a password for a two-letter name
     * would refuse ordinary passphrases that merely contain those two letters.
     */
    private static final int SHORTEST_EMBEDDED_TERM = 3;

    /**
     * A broken rule, and the message key that explains it, in the order {@link #check} tests them.
     * No message quotes the password.
     */
    public enum Violation {
        TOO_SHORT("validation.password.size"),
        MISSING_UPPERCASE("validation.password.uppercase"),
        MISSING_NUMBER("validation.password.number"),
        MISSING_SYMBOL("validation.password.symbol"),
        TOO_LONG("validation.password.tooLong"),
        REPETITIVE("validation.password.repetitive"),
        COMMON("validation.password.common"),
        PERSONAL("validation.password.personal");

        private final String messageKey;

        Violation(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    private final Set<String> blocklist;

    PasswordPolicy(Set<String> blocklist) {
        this.blocklist = Set.copyOf(blocklist);
    }

    /** The policy with the bundled list, which is read once per JVM, on first use. */
    public static PasswordPolicy standard() {
        return Standard.INSTANCE;
    }

    /**
     * The first rule the password breaks without reference to any account, or empty if it breaks
     * none. The four shown requirements come first, in the order the client lists them, so both
     * name the same missing requirement. {@code null} passes: whether a password was sent at all is
     * {@code @NotBlank}'s question, and answering it here as well would report it twice.
     */
    public Optional<Violation> check(String password) {
        if (password == null) {
            return Optional.empty();
        }
        if (password.codePointCount(0, password.length()) < MIN_CODE_POINTS) {
            return Optional.of(Violation.TOO_SHORT);
        }
        if (!hasUppercase(password)) {
            return Optional.of(Violation.MISSING_UPPERCASE);
        }
        if (!hasNumber(password)) {
            return Optional.of(Violation.MISSING_NUMBER);
        }
        if (!hasSymbol(password)) {
            return Optional.of(Violation.MISSING_SYMBOL);
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            return Optional.of(Violation.TOO_LONG);
        }
        String comparable = comparable(password);
        if (isShortUnitRepeated(comparable)) {
            return Optional.of(Violation.REPETITIVE);
        }
        if (blocklist.contains(comparable)) {
            return Optional.of(Violation.COMMON);
        }
        if (SERVICE_NAMES.stream().anyMatch(name -> isTermWithFiller(comparable, name))) {
            return Optional.of(Violation.PERSONAL);
        }
        return Optional.empty();
    }

    /**
     * Whether the password is the account's own address, the part of it before the {@code @}, or
     * the holder's name — alone, or padded out with digits, punctuation and spaces. Those are the
     * first guesses of anyone who knows whose account it is, and the address is typed into the
     * sign-in form right above the password.
     */
    public boolean isAboutAccount(String password, String email, String fullName) {
        if (password == null) {
            return false;
        }
        String comparable = comparable(password);
        return identityTerms(email, fullName).stream().anyMatch(term -> isTermWithFiller(comparable, term));
    }

    /*
     * The three character requirements read the password as typed, never its comparable form: that
     * one is lower-cased, and NFKC would turn a full-width Ａ into the A these rules ask for.
     */

    private static boolean hasUppercase(String password) {
        return UPPERCASE.matcher(password).find();
    }

    private static boolean hasNumber(String password) {
        return NUMBER.matcher(password).find();
    }

    private static boolean hasSymbol(String password) {
        return SYMBOL.matcher(password).find();
    }

    /**
     * NFKC folds compatibility forms — full-width Latin, ligatures, Arabic presentation forms —
     * into the characters they display as, and lower-casing removes case, so that "PASSWORD…" and
     * "ｐａｓｓｗｏｒｄ…" meet the list entry they imitate. The list was generated in this same form.
     */
    static String comparable(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static List<String> identityTerms(String email, String fullName) {
        List<String> terms = new ArrayList<>();
        if (email != null && !email.isBlank()) {
            String address = comparable(email.strip());
            terms.add(address);
            int at = address.lastIndexOf('@');
            if (at > 0) {
                terms.add(address.substring(0, at));
            }
        }
        if (fullName != null && !fullName.isBlank()) {
            String name = comparable(fullName.strip());
            terms.add(name);
            terms.add(name.replaceAll("\\s+", ""));
        }
        return terms;
    }

    /** The term itself, or the term with nothing but digits, punctuation and spaces around it. */
    private static boolean isTermWithFiller(String comparable, String term) {
        if (term.isEmpty()) {
            return false;
        }
        if (comparable.equals(term)) {
            return true;
        }
        if (term.codePointCount(0, term.length()) < SHORTEST_EMBEDDED_TERM || !comparable.contains(term)) {
            return false;
        }
        return comparable.replace(term, "").codePoints().noneMatch(Character::isLetter);
    }

    private static boolean isShortUnitRepeated(String comparable) {
        int[] codePoints = comparable.codePoints().toArray();
        for (int unit = 1; unit <= LONGEST_REPEATED_UNIT && unit * 2 <= codePoints.length; unit++) {
            boolean repeats = true;
            for (int i = unit; i < codePoints.length && repeats; i++) {
                repeats = codePoints[i] == codePoints[i - unit];
            }
            if (repeats) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read from the classpath and nowhere else: no password is ever sent off this server to be
     * checked. A build without the file fails at startup (see {@code PasswordPolicyConfig}) rather
     * than quietly accepting every common password.
     */
    private static Set<String> loadBundledBlocklist() {
        InputStream in = PasswordPolicy.class.getResourceAsStream(BUNDLED_BLOCKLIST);
        if (in == null) {
            throw new IllegalStateException("Bundled password blocklist not found: " + BUNDLED_BLOCKLIST);
        }
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            return reader.lines()
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + BUNDLED_BLOCKLIST, e);
        }
    }

    private static final class Standard {
        private static final PasswordPolicy INSTANCE = new PasswordPolicy(loadBundledBlocklist());
    }
}
