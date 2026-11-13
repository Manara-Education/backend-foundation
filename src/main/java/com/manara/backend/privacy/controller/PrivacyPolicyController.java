package com.manara.backend.privacy.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.privacy.dto.PrivacyPolicyResponse;
import com.manara.backend.privacy.service.PrivacyPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publication of the privacy policy. Anonymous and read-only; there are no write operations,
 * because versions are published by deploying.
 */
@RestController
@RequestMapping("/api/v1/privacy-policy")
@RequiredArgsConstructor
public class PrivacyPolicyController {

    private final PrivacyPolicyService privacyPolicyService;

    /** The version in force, with its full text. {@code 404 PRIVACY_POLICY_NOT_PUBLISHED} before one is. */
    @GetMapping("/current")
    public ApiResponse<PrivacyPolicyResponse> current() {
        return ApiResponse.success(privacyPolicyService.current());
    }
}
