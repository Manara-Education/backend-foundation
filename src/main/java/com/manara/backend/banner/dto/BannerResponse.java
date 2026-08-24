package com.manara.backend.banner.dto;

import com.manara.backend.banner.model.BannerDisplayFrequency;
import com.manara.backend.banner.model.BannerStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A banner as its owner sees it: everything they typed, plus the two things only the server knows —
 * what the banner is doing right now, and when it was last touched.
 *
 * <p>{@code status} is computed rather than stored, and is sent so the management list and the
 * delivery rules cannot drift: a row badged "active" is active because the same clock that decides
 * whether learners see it said so.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BannerResponse {

    private Long id;
    private String internalName;
    private String title;
    private String description;
    private String imageUrl;
    private String callToActionLabel;
    private String callToActionUrl;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String timezone;
    private Integer priority;
    private boolean enabled;
    private boolean dismissible;
    private BannerDisplayFrequency displayFrequency;
    private boolean draft;
    private BannerStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
