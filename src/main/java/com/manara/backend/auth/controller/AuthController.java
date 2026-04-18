package com.manara.backend.auth.controller;

import com.manara.backend.auth.dto.*;
import com.manara.backend.auth.service.AuthService;
import com.manara.backend.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<@NonNull ApiResponse<MessageResponse>> register(
            @RequestBody @Valid RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.register(request)));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<@NonNull ApiResponse<AuthResponse>> verifyOtp(
            @RequestBody @Valid OtpVerifyRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.verifyOtp(request)));
    }

    @PostMapping("/resend-otp")
    public ResponseEntity<@NonNull ApiResponse<MessageResponse>> resendOtp(
            @RequestBody @Valid ResendOtpRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.resendOtp(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<@NonNull ApiResponse<AuthResponse>> login(
            @RequestBody @Valid LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(request)));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<@NonNull ApiResponse<MessageResponse>> forgotPassword(
            @RequestBody @Valid ForgotPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.forgotPassword(request)));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<@NonNull ApiResponse<MessageResponse>> resetPassword(
            @RequestBody @Valid ResetPasswordRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.resetPassword(request)));
    }
}
