package com.manara.backend.profile.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.profile.dto.EmailChangeChallengeResponse;
import com.manara.backend.profile.dto.EmailChangeResendRequest;
import com.manara.backend.profile.dto.EmailChangeStartRequest;
import com.manara.backend.profile.dto.EmailChangeVerifyRequest;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.service.EmailChangeService;
import com.manara.backend.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Changing the signed-in account's email address. See {@link EmailChangeService} for the lifecycle
 * and what each answer may and may not reveal.
 */
@RestController
@RequestMapping("/api/v1/profile/email/change-requests")
@RequiredArgsConstructor
public class ProfileEmailController {

    private final EmailChangeService emailChangeService;

    @PostMapping
    public ApiResponse<EmailChangeChallengeResponse> start(@AuthenticationPrincipal User user,
                                                           @RequestBody @Valid EmailChangeStartRequest request) {
        return ApiResponse.success(emailChangeService.start(user, request));
    }

    @PostMapping("/resend")
    public ApiResponse<EmailChangeChallengeResponse> resend(@AuthenticationPrincipal User user,
                                                            @RequestBody @Valid EmailChangeResendRequest request) {
        return ApiResponse.success(emailChangeService.resend(user, request.getRequestId()));
    }

    /** Succeeding ends every other session on the account and issues this caller a new one. */
    @PostMapping("/verify")
    public ApiResponse<ProfileResponse> verify(@AuthenticationPrincipal User user,
                                               @RequestBody @Valid EmailChangeVerifyRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        return ApiResponse.success(emailChangeService.verify(
                user, request.getRequestId(), request.getCode(), httpRequest, httpResponse));
    }
}
