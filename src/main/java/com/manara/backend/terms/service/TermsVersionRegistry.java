package com.manara.backend.terms.service;

import com.manara.backend.terms.model.TermsVersion;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Every version of the Terms and Conditions this build knows about, and which one is in force.
 *
 * <p>The backend is the sole authority on both. The frontend holds the text of each version, keyed
 * by the same ids, but has no notion of "current" and no constant of its own to fall out of step —
 * it asks, renders what it is told, and sends that id back with a registration.
 *
 * <h2>Constants, never a clock</h2>
 * {@link #EFFECTIVE_DATE} is the date the version took effect, written down. It is emphatically not
 * {@code LocalDate.now()}: a date computed at request time would report a different "effective
 * date" every day, would differ between two instances configured in different zones, and would make
 * an acceptance record point at a version whose meaning changes after the fact.
 *
 * <h2>Why {@link #current()} returns an {@code Optional}</h2>
 * With the registry as it stands the empty case is unreachable — {@link #CURRENT_ID} names an entry
 * that is right there in {@link #KNOWN}. The seam is kept anyway, and its callers handle it, so that
 * a registry which later resolves its answer from configuration or from another service cannot turn
 * "I do not know which version is current" into "no version was required". Consent is refused when
 * the question cannot be answered; it is never assumed.
 */
@Component
public class TermsVersionRegistry {

    /** The date {@code 1.0} took effect. A fact about the document, fixed at publication. */
    static final LocalDate EFFECTIVE_DATE = LocalDate.of(2026, 9, 7);

    /** The id of the version a registration must carry today. */
    static final String CURRENT_ID = "1.0";

    /**
     * Every version ever published, oldest first.
     *
     * <p>Superseded versions stay here forever. They are what keeps an old acceptance record
     * meaningful: {@code "1.0"} on a row written years from now still has to name the text that was
     * in force when it was written, and a registry that had forgotten it would leave that row
     * pointing at nothing. A list rather than a map because this is read once per registration and
     * will hold a handful of entries for the life of the product.
     */
    private static final List<TermsVersion> KNOWN = List.of(
            new TermsVersion(CURRENT_ID, EFFECTIVE_DATE));

    /**
     * The version in force, or empty when this build cannot name one.
     *
     * <p>A lookup rather than a stored reference, so {@link #CURRENT_ID} and {@link #KNOWN} cannot
     * silently disagree: naming a version that is not in the registry yields an empty answer and a
     * refused registration, rather than a current version with no text behind it.
     */
    public Optional<TermsVersion> current() {
        return find(CURRENT_ID);
    }

    /** The named version, or empty when this build has never published it. */
    public Optional<TermsVersion> find(String id) {
        return KNOWN.stream().filter(version -> version.id().equals(id)).findFirst();
    }

    /**
     * Whether this build has ever published the named version.
     *
     * <p>Used only to tell the log which of two refusals happened — an id nobody has ever published,
     * or a real but superseded one. The client is told the same thing either way, because the remedy
     * is the same: re-read the current terms and accept them.
     */
    public boolean isKnown(String id) {
        return find(id).isPresent();
    }
}
