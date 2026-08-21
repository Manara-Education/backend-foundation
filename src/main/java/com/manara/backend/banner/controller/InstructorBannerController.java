package com.manara.backend.banner.controller;

import com.manara.backend.banner.dto.BannerOrderRequest;
import com.manara.backend.banner.dto.BannerRequest;
import com.manara.backend.banner.dto.BannerResponse;
import com.manara.backend.banner.service.BannerService;
import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Banner authoring. Every route is scoped to the caller's own banners — the ids in these paths are
 * resolved against the signed-in instructor, never on their own.
 */
@RestController
@RequestMapping("/api/v1/instructor/banners")
@RequiredArgsConstructor
public class InstructorBannerController {

    private final BannerService bannerService;
    private final MessageService messageService;

    @GetMapping
    public ApiResponse<List<BannerResponse>> getMyBanners(@AuthenticationPrincipal User user) {
        return ApiResponse.success(bannerService.getMyBanners(user));
    }

    @GetMapping("/{bannerId}")
    public ApiResponse<BannerResponse> getBanner(
            @AuthenticationPrincipal User user,
            @PathVariable Long bannerId) {
        return ApiResponse.success(bannerService.getBanner(user, bannerId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BannerResponse> createBanner(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BannerRequest request) {
        return ApiResponse.success(bannerService.createBanner(user, request));
    }

    @PutMapping("/{bannerId}")
    public ApiResponse<BannerResponse> updateBanner(
            @AuthenticationPrincipal User user,
            @PathVariable Long bannerId,
            @Valid @RequestBody BannerRequest request) {
        return ApiResponse.success(bannerService.updateBanner(user, bannerId, request));
    }

    /**
     * Reordering answers with the whole list rather than a message: the drag rewrote every row's
     * position, so the list the client is holding is stale in more places than the one it moved.
     */
    @PutMapping("/order")
    public ApiResponse<List<BannerResponse>> reorderBanners(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody BannerOrderRequest request) {
        return ApiResponse.success(bannerService.reorderBanners(user, request));
    }

    @DeleteMapping("/{bannerId}")
    public ApiResponse<MessageResponse> deleteBanner(
            @AuthenticationPrincipal User user,
            @PathVariable Long bannerId) {
        bannerService.deleteBanner(user, bannerId);
        return ApiResponse.success(MessageResponse.builder()
                .message(messageService.get("banner.deleted"))
                .build());
    }
}
