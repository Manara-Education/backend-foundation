package com.manara.backend.banner.controller;

import com.manara.backend.banner.dto.StudentBannerResponse;
import com.manara.backend.banner.service.StudentBannerService;
import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Banner delivery. Two routes: what to show this learner, and the one thing they can say back.
 *
 * <p>The response shape is {@link StudentBannerResponse} rather than the owner's, so nothing about
 * how a banner is scheduled or filed reaches the browser it is displayed in.
 */
@RestController
@RequestMapping("/api/v1/student/banners")
@RequiredArgsConstructor
public class StudentBannerController {

    private final StudentBannerService studentBannerService;
    private final MessageService messageService;

    @GetMapping
    public ApiResponse<List<StudentBannerResponse>> getActiveBanners(@AuthenticationPrincipal User user) {
        return ApiResponse.success(studentBannerService.getActiveBanners(user));
    }

    /**
     * Only meaningful for a banner set to be shown once per learner; the shorter modes are the
     * client's own to forget, and asking the server to remember them is refused rather than
     * silently accepted.
     */
    @PostMapping("/{bannerId}/dismiss")
    public ApiResponse<MessageResponse> dismissBanner(
            @AuthenticationPrincipal User user,
            @PathVariable Long bannerId) {
        studentBannerService.dismissBanner(user, bannerId);
        return ApiResponse.success(MessageResponse.builder()
                .message(messageService.get("banner.dismissed"))
                .build());
    }
}
