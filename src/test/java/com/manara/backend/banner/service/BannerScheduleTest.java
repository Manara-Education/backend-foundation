package com.manara.backend.banner.service;

import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.model.BannerStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single answer to "what is this banner doing right now".
 *
 * <p>The cases that matter are the ones where two reasons for not showing overlap: a draft that is
 * also enabled and in-window, and a switched-off banner whose dates have run out. Whichever answer
 * comes back is the one the owner reads on the row and the one delivery acts on, so they cannot be
 * allowed to disagree.
 */
class BannerScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 12, 0);

    private final BannerSchedule bannerSchedule =
            new BannerSchedule(Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC")));

    @Test
    void aDraftIsADraftEvenWhenEnabledAndInsideItsWindow() {
        Banner banner = banner(NOW.minusDays(1), NOW.plusDays(1), true, true);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.DRAFT);
    }

    @Test
    void aPublishedBannerThatWasSwitchedOffIsInactiveRatherThanExpired() {
        Banner banner = banner(NOW.minusDays(9), NOW.minusDays(2), false, false);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.INACTIVE);
    }

    @Test
    void aStartStillAheadIsScheduled() {
        Banner banner = banner(NOW.plusMinutes(1), NOW.plusDays(7), true, false);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.SCHEDULED);
    }

    @Test
    void anEndAlreadyPassedIsExpired() {
        Banner banner = banner(NOW.minusDays(7), NOW.minusMinutes(1), true, false);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.EXPIRED);
    }

    @Test
    void aBannerInsideItsWindowIsActive() {
        Banner banner = banner(NOW.minusDays(1), NOW.plusDays(1), true, false);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.ACTIVE);
    }

    /** An open start means "already running", not "no start yet". */
    @Test
    void aBannerWithNoStartIsAlreadyRunning() {
        Banner banner = banner(null, NOW.plusDays(1), true, false);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.ACTIVE);
    }

    /** An open end means "until switched off", not "already over". */
    @Test
    void aBannerWithNoEndRunsUntilItIsSwitchedOff() {
        Banner banner = banner(NOW.minusYears(1), null, true, false);

        assertThat(bannerSchedule.statusOf(banner)).isEqualTo(BannerStatus.ACTIVE);
    }

    /** The boundaries are inclusive: the minute a banner starts, it is running. */
    @Test
    void theStartInstantItselfIsAlreadyActive() {
        assertThat(bannerSchedule.statusOf(banner(NOW, NOW.plusDays(1), true, false)))
                .isEqualTo(BannerStatus.ACTIVE);
        assertThat(bannerSchedule.statusOf(banner(NOW.minusDays(1), NOW, true, false)))
                .isEqualTo(BannerStatus.ACTIVE);
    }

    private Banner banner(LocalDateTime startAt, LocalDateTime endAt, boolean enabled, boolean draft) {
        return Banner.builder()
                .id(1L)
                .internalName("internal")
                .title("title")
                .timezone("Asia/Riyadh")
                .priority(1)
                .startAt(startAt)
                .endAt(endAt)
                .enabled(enabled)
                .draft(draft)
                .build();
    }
}
