package com.manara.backend.profile.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.profile.dto.InstructorProfileResponse;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.dto.UpdateInstructorProfileRequest;
import com.manara.backend.profile.dto.UpdateProfileRequest;
import com.manara.backend.profile.service.ProfileAvatarService;
import com.manara.backend.profile.service.ProfileService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;
    private final ProfileAvatarService profileAvatarService;

    @GetMapping
    public ApiResponse<ProfileResponse> getProfile(@AuthenticationPrincipal User user) {
        return ApiResponse.success(profileService.getProfile(user));
    }

    /**
     * Renames the caller and answers with the updated profile. The previous build answered with a
     * message only; the deployed client ignores the body of this call, so the change is additive
     * for it.
     */
    @PutMapping
    public ApiResponse<ProfileResponse> updateProfile(@AuthenticationPrincipal User user,
                                                      @RequestBody @Valid UpdateProfileRequest request) {
        return ApiResponse.success(profileService.updateProfile(user, request));
    }

    /** Replaces the caller's photo. The image is re-encoded server-side; see {@link ProfileAvatarService}. */
    @PostMapping(path = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProfileResponse> replaceAvatar(@AuthenticationPrincipal User user,
                                                      @RequestParam("file") MultipartFile file) {
        return ApiResponse.success(profileAvatarService.replaceAvatar(user, file));
    }

    @DeleteMapping("/avatar")
    public ApiResponse<ProfileResponse> removeAvatar(@AuthenticationPrincipal User user) {
        return ApiResponse.success(profileAvatarService.removeAvatar(user));
    }

    @GetMapping("/instructor")
    public ApiResponse<InstructorProfileResponse> getInstructorProfile(@AuthenticationPrincipal User user) {
        return ApiResponse.success(profileService.getInstructorProfile(user));
    }

    /** The instructor's public headline, shown on their course pages. */
    @PutMapping("/instructor")
    public ApiResponse<InstructorProfileResponse> updateInstructorProfile(
            @AuthenticationPrincipal User user, @RequestBody @Valid UpdateInstructorProfileRequest request) {
        return ApiResponse.success(profileService.updateInstructorProfile(user, request));
    }
}
