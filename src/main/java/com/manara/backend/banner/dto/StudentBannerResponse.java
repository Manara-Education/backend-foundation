package com.manara.backend.banner.dto;

import com.manara.backend.banner.model.BannerDisplayFrequency;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A banner as a learner receives it — the six fields the carousel renders and the two that decide
 * how it behaves, and nothing else.
 *
 * <p>Deliberately not a trimmed {@link BannerResponse}: the owner's internal name, its schedule, its
 * position in their list and its draft flag are all management detail. Shipping them because they
 * happen to be on the entity is how an internal label ends up in a browser's network tab.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class StudentBannerResponse {

    private Long id;
    private String title;
    private String description;
    private String imageUrl;
    private String callToActionLabel;
    private String callToActionUrl;

    /** Whether the learner may close it at all — the close control is hidden when false. */
    private boolean dismissible;

    /** How long a dismissal lasts, which is what tells the client whether to tell the server. */
    private BannerDisplayFrequency displayFrequency;
}
