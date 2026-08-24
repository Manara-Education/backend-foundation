package com.manara.backend.banner.service;

import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.model.BannerStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * The one place a banner's standing is decided.
 *
 * <p>Both sides of the feature ask it the same question: the management list badges each row with
 * the answer, and delivery ships exactly the banners it calls {@link BannerStatus#ACTIVE}. Having a
 * single implementation is what keeps a row from reading "active" to its owner while learners are
 * not being shown it.
 *
 * <p>The clock is injected rather than read from {@code LocalDateTime.now()} so the boundaries — the
 * minute a banner starts, the minute it stops — can be tested at a fixed instant.
 */
@Component
@RequiredArgsConstructor
public class BannerSchedule {

    private final Clock clock;

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * Order matters: a draft is a draft even when enabled and inside its window, and a switched-off
     * banner is inactive rather than expired, because its owner turned it off and its dates are no
     * longer the reason it is not showing.
     */
    public BannerStatus statusOf(Banner banner) {
        if (banner.isDraft()) {
            return BannerStatus.DRAFT;
        }
        if (!banner.isEnabled()) {
            return BannerStatus.INACTIVE;
        }
        LocalDateTime now = now();
        if (banner.getStartAt() != null && banner.getStartAt().isAfter(now)) {
            return BannerStatus.SCHEDULED;
        }
        if (banner.getEndAt() != null && banner.getEndAt().isBefore(now)) {
            return BannerStatus.EXPIRED;
        }
        return BannerStatus.ACTIVE;
    }
}
