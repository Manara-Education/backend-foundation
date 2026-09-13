package com.manara.backend.terms.repository;

import com.manara.backend.terms.model.TermsAcceptance;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for consent records.
 *
 * <p>Deliberately bare. Registration is the only writer and there is no reader yet, so there is no
 * finder here to go stale — and none should be added speculatively. When one is, it must not treat
 * an empty result as a refusal: accounts that predate the table have no row, and that absence means
 * "unknown". See {@link TermsAcceptance}.
 */
public interface TermsAcceptanceRepository extends JpaRepository<@NonNull TermsAcceptance, @NonNull Long> {
}
