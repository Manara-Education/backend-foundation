package com.manara.backend.banner.service;

import com.manara.backend.banner.dto.BannerOrderRequest;
import com.manara.backend.banner.dto.BannerRequest;
import com.manara.backend.banner.mapper.BannerMapper;
import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.model.BannerDisplayFrequency;
import com.manara.backend.banner.model.BannerStatus;
import com.manara.backend.banner.repository.BannerDismissalRepository;
import com.manara.backend.banner.repository.BannerRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Authoring, and the gate every one of its routes goes through.
 *
 * <p>A banner is always reached by an id the caller supplied, so the tests that matter most here
 * are the ones where the id is real and the caller is not its owner.
 */
@ExtendWith(MockitoExtension.class)
class BannerServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 12, 0);
    private static final Long OWNER_INSTRUCTOR_ID = 11L;

    @Mock
    private BannerRepository bannerRepository;

    @Mock
    private BannerDismissalRepository bannerDismissalRepository;

    @Mock
    private InstructorRepository instructorRepository;

    private BannerService bannerService;

    private final User instructorUser = User.builder().id(3L).role(Role.INSTRUCTOR).build();
    private final User studentUser = User.builder().id(4L).role(Role.STUDENT).build();
    private final Instructor owner = Instructor.builder().id(OWNER_INSTRUCTOR_ID).user(instructorUser).build();

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        bannerService = new BannerService(
                bannerRepository,
                bannerDismissalRepository,
                instructorRepository,
                new BannerMapper(),
                new BannerValidator(),
                new BannerSchedule(fixed));
    }

    // ── Role and ownership ────────────────────────────────────────────────────

    @Test
    void aLearnerCannotManageBanners() {
        assertThatThrownBy(() -> bannerService.getMyBanners(studentUser))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.onlyInstructor");
    }

    /** Another instructor's banner is reported missing: its id is theirs to know about, not ours. */
    @Test
    void anotherInstructorsBannerIsNotFoundRatherThanForbidden() {
        givenInstructor();
        given(bannerRepository.findByIdAndInstructorId(99L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bannerService.getBanner(instructorUser, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.banner.notFound");
    }

    @Test
    void anotherInstructorsBannerCannotBeUpdated() {
        givenInstructor();
        given(bannerRepository.findByIdAndInstructorId(99L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bannerService.updateBanner(instructorUser, 99L, validRequest().build()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(bannerRepository, never()).save(any());
    }

    @Test
    void anotherInstructorsBannerCannotBeDeleted() {
        givenInstructor();
        given(bannerRepository.findByIdAndInstructorId(99L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> bannerService.deleteBanner(instructorUser, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(bannerRepository, never()).delete(any());
    }

    // ── Create ────────────────────────────────────────────────────────────────

    /** The form has no field for a position, so a new banner joins the end of its owner's list. */
    @Test
    void aNewBannerIsAppendedToTheEndOfItsOwnersList() {
        givenInstructor();
        given(bannerRepository.findHighestPriority(OWNER_INSTRUCTOR_ID)).willReturn(4);
        givenSaveReturnsArgument();

        var created = bannerService.createBanner(instructorUser, validRequest().build());

        assertThat(created.getPriority()).isEqualTo(5);
    }

    @Test
    void theFirstBannerAnInstructorCreatesTakesTheFirstPosition() {
        givenInstructor();
        given(bannerRepository.findHighestPriority(OWNER_INSTRUCTOR_ID)).willReturn(0);
        givenSaveReturnsArgument();

        assertThat(bannerService.createBanner(instructorUser, validRequest().build()).getPriority()).isEqualTo(1);
    }

    /** Saving as a draft also switches it off — a draft is never one toggle away from being live. */
    @Test
    void savingAsADraftSwitchesTheBannerOff() {
        givenInstructor();
        given(bannerRepository.findHighestPriority(OWNER_INSTRUCTOR_ID)).willReturn(0);
        givenSaveReturnsArgument();

        var created = bannerService.createBanner(instructorUser,
                validRequest().draft(true).enabled(true).build());

        assertThat(created.isDraft()).isTrue();
        assertThat(created.isEnabled()).isFalse();
        assertThat(created.getStatus()).isEqualTo(BannerStatus.DRAFT);
    }

    @Test
    void anInvalidPayloadIsRefusedBeforeAnythingIsWritten() {
        givenInstructor();

        assertThatThrownBy(() -> bannerService.createBanner(instructorUser,
                validRequest().callToActionLabel("سجّل").callToActionUrl(null).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.ctaUrlRequired");
        verify(bannerRepository, never()).save(any());
    }

    @Test
    void aCreatedBannerCarriesItsComputedStatus() {
        givenInstructor();
        given(bannerRepository.findHighestPriority(OWNER_INSTRUCTOR_ID)).willReturn(0);
        givenSaveReturnsArgument();

        var created = bannerService.createBanner(instructorUser,
                validRequest().startAt(NOW.plusDays(2)).endAt(NOW.plusDays(9)).build());

        assertThat(created.getStatus()).isEqualTo(BannerStatus.SCHEDULED);
    }

    // ── Update ────────────────────────────────────────────────────────────────

    /** Editing a banner must not move it: the form the edit came from has no position field. */
    @Test
    void anEditKeepsTheBannersPositionWhenThePayloadDoesNotMentionOne() {
        givenInstructor();
        Banner existing = banner(7L, 3);
        given(bannerRepository.findByIdAndInstructorId(7L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.of(existing));
        givenSaveReturnsArgument();

        var updated = bannerService.updateBanner(instructorUser, 7L, validRequest().build());

        assertThat(updated.getPriority()).isEqualTo(3);
    }

    /** Full replacement: an optional field the payload leaves out is cleared, not kept. */
    @Test
    void anOmittedOptionalFieldIsClearedRatherThanKept() {
        givenInstructor();
        Banner existing = banner(7L, 1);
        existing.setDescription("the old supporting line");
        existing.setImageUrl("https://cdn.manara.com/old.png");
        given(bannerRepository.findByIdAndInstructorId(7L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.of(existing));
        givenSaveReturnsArgument();

        var updated = bannerService.updateBanner(instructorUser, 7L, validRequest().build());

        assertThat(updated.getDescription()).isNull();
        assertThat(updated.getImageUrl()).isNull();
    }

    /** Switching a draft on is the list's toggle, and it has to leave draft behind to take effect. */
    @Test
    void publishingADraftFromTheListMakesItActive() {
        givenInstructor();
        Banner existing = banner(7L, 1);
        existing.setDraft(true);
        existing.setEnabled(false);
        given(bannerRepository.findByIdAndInstructorId(7L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.of(existing));
        givenSaveReturnsArgument();

        var updated = bannerService.updateBanner(instructorUser, 7L,
                validRequest().draft(false).enabled(true).build());

        assertThat(updated.getStatus()).isEqualTo(BannerStatus.ACTIVE);
    }

    @Test
    void switchingABannerOffMakesItInactive() {
        givenInstructor();
        Banner existing = banner(7L, 1);
        given(bannerRepository.findByIdAndInstructorId(7L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.of(existing));
        givenSaveReturnsArgument();

        var updated = bannerService.updateBanner(instructorUser, 7L, validRequest().enabled(false).build());

        assertThat(updated.getStatus()).isEqualTo(BannerStatus.INACTIVE);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** A dismissal records a refusal of this banner and nothing else; it goes with it. */
    @Test
    void deletingABannerTakesTheLearnersDismissalsWithIt() {
        givenInstructor();
        Banner existing = banner(7L, 1);
        given(bannerRepository.findByIdAndInstructorId(7L, OWNER_INSTRUCTOR_ID)).willReturn(Optional.of(existing));

        bannerService.deleteBanner(instructorUser, 7L);

        verify(bannerDismissalRepository).deleteByBannerId(7L);
        verify(bannerRepository).delete(existing);
    }

    // ── Reorder ───────────────────────────────────────────────────────────────

    @Test
    void reorderingRewritesEveryPositionFromTheOrderItIsGiven() {
        givenInstructor();
        var owned = new ArrayList<>(List.of(banner(1L, 1), banner(2L, 2), banner(3L, 3)));
        given(bannerRepository.findOwnedOrdered(OWNER_INSTRUCTOR_ID)).willReturn(owned);

        var reordered = bannerService.reorderBanners(instructorUser,
                BannerOrderRequest.builder().bannerIds(List.of(3L, 1L, 2L)).build());

        assertThat(reordered).extracting("id").containsExactly(3L, 1L, 2L);
        assertThat(reordered).extracting("priority").containsExactly(1, 2, 3);
        verify(bannerRepository).saveAll(any());
    }

    /**
     * The client's list can be a moment behind — a second tab, a banner created since the screen
     * loaded. Honouring the drag and leaving the unmentioned banner below is a better answer than
     * refusing what the owner just did.
     */
    @Test
    void aBannerTheClientDidNotKnowAboutIsPlacedAfterTheOnesItOrdered() {
        givenInstructor();
        var owned = new ArrayList<>(List.of(banner(1L, 1), banner(2L, 2), banner(3L, 3)));
        given(bannerRepository.findOwnedOrdered(OWNER_INSTRUCTOR_ID)).willReturn(owned);

        var reordered = bannerService.reorderBanners(instructorUser,
                BannerOrderRequest.builder().bannerIds(List.of(2L, 1L)).build());

        assertThat(reordered).extracting("id").containsExactly(2L, 1L, 3L);
        assertThat(reordered).extracting("priority").containsExactly(1, 2, 3);
    }

    @Test
    void anotherInstructorsBannerCannotBeSlippedIntoAnOrdering() {
        givenInstructor();
        given(bannerRepository.findOwnedOrdered(OWNER_INSTRUCTOR_ID))
                .willReturn(new ArrayList<>(List.of(banner(1L, 1))));

        assertThatThrownBy(() -> bannerService.reorderBanners(instructorUser,
                BannerOrderRequest.builder().bannerIds(List.of(1L, 404L)).build()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.banner.notFound");
        verify(bannerRepository, never()).saveAll(any());
    }

    @Test
    void anOrderingThatNamesTheSameBannerTwiceIsRefused() {
        givenInstructor();
        given(bannerRepository.findOwnedOrdered(OWNER_INSTRUCTOR_ID))
                .willReturn(new ArrayList<>(List.of(banner(1L, 1), banner(2L, 2))));

        assertThatThrownBy(() -> bannerService.reorderBanners(instructorUser,
                BannerOrderRequest.builder().bannerIds(List.of(1L, 2L, 1L)).build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.orderDuplicate");
        verify(bannerRepository, never()).saveAll(any());
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void givenInstructor() {
        given(instructorRepository.findByUserId(instructorUser.getId())).willReturn(Optional.of(owner));
    }

    private void givenSaveReturnsArgument() {
        given(bannerRepository.save(any(Banner.class))).willAnswer(invocation -> invocation.getArgument(0));
    }

    private BannerRequest.BannerRequestBuilder validRequest() {
        return BannerRequest.builder()
                .internalName("عرض رمضان")
                .title("خصم ٣٠٪ على دورة النحو المتقدم")
                .startAt(NOW.minusDays(1))
                .endAt(NOW.plusDays(10))
                .timezone("Asia/Riyadh")
                .displayFrequency(BannerDisplayFrequency.EVERY_VISIT);
    }

    private Banner banner(Long id, int priority) {
        return Banner.builder()
                .id(id)
                .instructor(owner)
                .internalName("internal-" + id)
                .title("title-" + id)
                .timezone("Asia/Riyadh")
                .priority(priority)
                .startAt(NOW.minusDays(1))
                .endAt(NOW.plusDays(10))
                .enabled(true)
                .dismissible(true)
                .displayFrequency(BannerDisplayFrequency.EVERY_VISIT)
                .draft(false)
                .build();
    }
}
