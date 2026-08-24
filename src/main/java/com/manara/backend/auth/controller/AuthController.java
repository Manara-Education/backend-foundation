package com.manara.backend.auth.controller;

import com.manara.backend.auth.dto.*;
import com.manara.backend.auth.service.AuthService;
import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MessageResponse> register(@RequestBody @Valid RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/verify-otp")
    public ApiResponse<AuthResponse> verifyOtp(@RequestBody @Valid OtpVerifyRequest request,
                                               HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.verifyOtp(request, httpRequest, httpResponse));
    }

    @PostMapping("/resend-otp")
    public ApiResponse<MessageResponse> resendOtp(@RequestBody @Valid ResendOtpRequest request) {
        return ApiResponse.success(authService.resendOtp(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@RequestBody @Valid LoginRequest request,
                                           HttpServletRequest httpRequest,
                                           HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.login(request, httpRequest, httpResponse));
    }

    @PostMapping("/forgot-password")
    public ApiResponse<MessageResponse> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        return ApiResponse.success(authService.forgotPassword(request));
    }

    @PostMapping("/verify-reset-otp")
    public ApiResponse<MessageResponse> verifyResetOtp(@RequestBody @Valid OtpVerifyRequest request) {
        return ApiResponse.success(authService.verifyResetOtp(request));
    }

    @PostMapping("/reset-password")
    public ApiResponse<MessageResponse> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        return ApiResponse.success(authService.resetPassword(request));
    }

    /**
     * Changes the password of the signed-in account. Unlike {@code /reset-password}, which is
     * anonymous and carries an emailed OTP, this one authenticates by session and proves the
     * caller knows the account by asking for the current password. Succeeding here also clears
     * a forced-reset requirement, which is what reopens the rest of the API to the account.
     */
    @PostMapping("/change-password")
    public ApiResponse<MessageResponse> changePassword(@AuthenticationPrincipal User user,
                                                       @RequestBody @Valid ChangePasswordRequest request) {
        return ApiResponse.success(authService.changePassword(user, request));
    }

    @PostMapping("/logout")
    public ApiResponse<MessageResponse> logout(HttpServletRequest httpRequest,
                                               HttpServletResponse httpResponse) {
        return ApiResponse.success(authService.logout(httpRequest, httpResponse));
    }

    /**
     * Returns the current user — frontend uses this in lieu of decoding a JWT, and on every
     * reload to restore the session. The service re-reads the row rather than mapping the
     * session principal, so state that changed after sign-in (notably the forced-reset flag)
     * is reported as the database has it.
     */
    @GetMapping("/me")
    public ApiResponse<AuthResponse> me(@AuthenticationPrincipal User user) {
        return ApiResponse.success(authService.currentUser(user));
    }

    /**
     * Bootstraps the CSRF cookie. SPAs call this once on load so the XSRF-TOKEN cookie is set,
     * then echo it on subsequent state-changing requests via the X-XSRF-TOKEN header.
     */
    @GetMapping("/csrf")
    public ResponseEntity<Void> csrf(@RequestAttribute(name = "_csrf", required = false) CsrfToken token) {
        if (token != null) {
            token.getToken();
        }
        return ResponseEntity.noContent().build();
    }
}
