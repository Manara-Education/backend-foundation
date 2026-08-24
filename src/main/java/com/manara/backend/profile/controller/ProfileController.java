package com.manara.backend.profile.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.dto.UpdateProfileRequest;
import com.manara.backend.profile.service.ProfileService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    @GetMapping
    public ApiResponse<ProfileResponse> getProfile(@AuthenticationPrincipal User user) {
        return ApiResponse.success(profileService.getProfile(user));
    }

    @PutMapping
    public ApiResponse<MessageResponse> updateProfile(@AuthenticationPrincipal User user,
                                                      @RequestBody @Valid UpdateProfileRequest request) {
        return ApiResponse.success(profileService.updateProfile(user, request));
    }
}
