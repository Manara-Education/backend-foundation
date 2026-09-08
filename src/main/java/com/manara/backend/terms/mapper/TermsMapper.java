package com.manara.backend.terms.mapper;

import com.manara.backend.terms.dto.TermsVersionResponse;
import com.manara.backend.terms.model.TermsAcceptance;
import com.manara.backend.terms.model.TermsVersion;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Builds the terms feature's entity and its response DTO, and nothing else.
 *
 * <p>Pure by construction: no clock, no registry, no repository. {@code acceptedAt} arrives as a
 * parameter because the service is the layer allowed to ask what time it is — a mapper that read a
 * clock would be untestable at a fixed instant and would put a second, unmanaged source of "now"
 * into the application.
 */
@Component
public class TermsMapper {

    public TermsVersionResponse toResponse(TermsVersion version) {
        return TermsVersionResponse.builder()
                .version(version.id())
                .effectiveDate(version.effectiveDate())
                .build();
    }

    /**
     * Builds the consent record for an account.
     *
     * <p>The version is stored as the id of the version the server resolved, never as the string the
     * request happened to send. They are equal by the time this is called — the service refuses the
     * registration otherwise — and taking the registry's copy is what keeps that true of the row
     * even if the two ever stop being the same thing.
     */
    public TermsAcceptance toAcceptance(User user, TermsVersion version, Instant acceptedAt) {
        return TermsAcceptance.builder()
                .user(user)
                .termsVersion(version.id())
                .acceptedAt(acceptedAt)
                .build();
    }
}
