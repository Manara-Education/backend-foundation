package com.manara.backend.terms.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.terms.dto.TermsVersionResponse;
import com.manara.backend.terms.service.TermsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publication of the Terms and Conditions version.
 *
 * <p>Unauthenticated, because the people who most need it have no account yet: the registration
 * form asks this before it can offer anything to accept. It is equally reachable while signed in —
 * a user must be able to re-read what they agreed to.
 */
@RestController
@RequestMapping("/api/v1/terms")
@RequiredArgsConstructor
public class TermsController {

    private final TermsService termsService;

    /**
     * The version in force and the date it took effect.
     *
     * <p>{@code 503} when the server cannot name one — see {@code TermsUnavailableException}. It
     * never guesses, and never falls back to an older version.
     */
    @GetMapping("/current")
    public ApiResponse<TermsVersionResponse> currentVersion() {
        return ApiResponse.success(termsService.currentVersion());
    }
}
