package com.manara.backend.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule everything else depends on: which strings name the same account.
 *
 * <p>If this is wrong, every other guarantee in the system is wrong with it — the column holds
 * something other than what lookups search for, and the database's unique index protects a value
 * nobody ever queries by.
 */
class EmailAddressTest {

    private static final String CANONICAL = "ali@x.com";

    @ParameterizedTest(name = "\"{0}\" names the same account as ali@x.com")
    @ValueSource(strings = {
            "ali@x.com",
            "Ali@x.com",
            "ALI@X.COM",
            "aLi@X.cOm",
            "  Ali@x.com  ",
            "\tALI@X.COM\n",
            "   ali@x.com",
            "ali@x.com   ",
    })
    @DisplayName("case and surrounding whitespace never distinguish two addresses")
    void canonicalisesCaseAndWhitespace(String raw) {
        assertThat(EmailAddress.canonical(raw)).isEqualTo(CANONICAL);
    }

    @Test
    @DisplayName("canonicalising an already-canonical address changes nothing")
    void isIdempotent() {
        String once = EmailAddress.canonical("  Ali@X.com ");
        assertThat(EmailAddress.canonical(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("null passes through, so repository lookups need no null check of their own")
    void tolerantOfNull() {
        assertThat(EmailAddress.canonical(null)).isNull();
    }

    @Test
    @DisplayName("a blank value stays blank rather than becoming null — @NotBlank owns that case")
    void leavesBlankAlone() {
        assertThat(EmailAddress.canonical("   ")).isEmpty();
    }

    @Test
    @DisplayName("addresses that genuinely differ stay different")
    void doesNotMergeDistinctAddresses() {
        // Dots and +tags are provider conventions, not properties of email. Folding them away
        // would merge accounts belonging to different people.
        assertThat(EmailAddress.canonical("a.li@x.com")).isNotEqualTo(CANONICAL);
        assertThat(EmailAddress.canonical("ali+work@x.com")).isNotEqualTo(CANONICAL);
    }

    /** Why {@link Locale#ROOT} is spelled out in {@link EmailAddress} rather than left to default. */
    @Nested
    @DisplayName("locale independence")
    class LocaleIndependence {

        @Test
        @DisplayName("a Turkish default locale does not change which account an address names")
        void unaffectedByTurkishLocale() {
            Locale original = Locale.getDefault();
            try {
                Locale.setDefault(Locale.forLanguageTag("tr-TR"));

                // The trap: "ALI@X.COM".toLowerCase() under tr-TR yields "alı@x.com" — dotless ı,
                // a different string. The account a user reached would depend on the server's
                // locale.
                assertThat("ALI@X.COM".toLowerCase()).isNotEqualTo(CANONICAL);
                assertThat(EmailAddress.canonical("ALI@X.COM")).isEqualTo(CANONICAL);
            } finally {
                Locale.setDefault(original);
            }
        }
    }
}
