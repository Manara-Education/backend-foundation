package com.manara.backend.terms.service;

import com.manara.backend.common.exception.ConflictException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.terms.dto.TermsVersionResponse;
import com.manara.backend.terms.exception.TermsUnavailableException;
import com.manara.backend.terms.mapper.TermsMapper;
import com.manara.backend.terms.model.TermsVersion;
import com.manara.backend.terms.repository.TermsAcceptanceRepository;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Publishes the current Terms and Conditions version, and decides whether a submitted acceptance
 * may stand.
 *
 * <p>Both halves of one authority. The endpoint that names the current version and the check that a
 * registration carried it read the same registry, so a client that does exactly what it was told a
 * moment ago cannot be refused for having been told something else.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TermsService {

    private final TermsVersionRegistry registry;
    private final TermsAcceptanceRepository termsAcceptanceRepository;
    private final TermsMapper termsMapper;
    private final Clock clock;

    /** The version in force, for {@code GET /api/v1/terms/current}. */
    public TermsVersionResponse currentVersion() {
        return termsMapper.toResponse(requireCurrentVersion());
    }

    /**
     * Checks the version a registration accepted, and returns the version it will be recorded
     * against.
     *
     * <p>Only the current version is accepted. An id nobody has published and a real but superseded
     * one are both refused with {@code TERMS_VERSION_OUTDATED}, deliberately: the client's remedy is
     * identical either way — re-read the current terms and accept them — so telling the two apart
     * would give a caller nothing to do differently. The log does distinguish them, because they say
     * different things about what went wrong (a client that invented a value versus one that was
     * simply open when a new version was published).
     *
     * <p>Note what is <em>not</em> checked here: whether the acceptance flag itself is true. That is
     * bean validation's job on the request DTO, where a missing or false value produces the
     * project's ordinary field-error response. This method is only ever reached with an explicit
     * {@code true} behind it.
     *
     * <p>Throws before anything is written, and is called before anything is written. Nothing about
     * an account exists at the point this decides.
     */
    public TermsVersion requireCurrentVersionAccepted(String submittedVersion) {
        TermsVersion current = requireCurrentVersion();

        if (current.id().equals(submittedVersion)) {
            return current;
        }

        // Version ids only; nothing about who was registering. This line must be safe to keep
        // forever in a log that outlives the account it refers to.
        if (registry.isKnown(submittedVersion)) {
            log.warn("Registration refused: terms version '{}' is superseded; current is '{}'",
                    submittedVersion, current.id());
        } else {
            log.warn("Registration refused: terms version '{}' is not one this build has published;"
                    + " current is '{}'", submittedVersion, current.id());
        }

        throw new ConflictException(ErrorCode.TERMS_VERSION_OUTDATED, "error.terms.versionOutdated");
    }

    /**
     * Records that this account accepted this version, now.
     *
     * <p>Joins the caller's transaction — {@code AuthService#register} is already
     * {@code @Transactional}, and {@code REQUIRED} propagation means this row and the account row
     * commit together or not at all. There is no path that leaves a consent record for an account
     * that was never created, and none that creates an account with no consent record.
     *
     * <p>The timestamp comes from the injected {@link Clock} and is a UTC {@link Instant}. Never
     * from the request: a client-supplied time is a client-supplied claim, and this row's only
     * purpose is to be evidence.
     */
    @Transactional
    public void recordAcceptance(User user, TermsVersion version) {
        Instant acceptedAt = clock.instant();
        termsAcceptanceRepository.save(termsMapper.toAcceptance(user, version, acceptedAt));
    }

    private TermsVersion requireCurrentVersion() {
        return registry.current().orElseThrow(() -> {
            log.error("No current terms version could be resolved; refusing to take consent");
            return new TermsUnavailableException("error.terms.unavailable");
        });
    }
}
