package com.manara.backend.profile.controller;

import com.manara.backend.auth.dto.MessageResponse;
import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.dto.UpdateProfileRequest;
import com.manara.backend.profile.service.ProfileService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ResponseEntity<@NonNull ApiResponse<ProfileResponse>> getProfile(
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(profileService.getProfile(user)));
    }

    @PutMapping
    public ResponseEntity<@NonNull ApiResponse<MessageResponse>> updateProfile(
            @AuthenticationPrincipal User user,
            @RequestBody @Valid UpdateProfileRequest request) {
        return ResponseEntity.ok(ApiResponse.success(profileService.updateProfile(user, request)));
    }
}