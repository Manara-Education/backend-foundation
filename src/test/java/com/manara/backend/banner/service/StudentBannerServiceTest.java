package com.manara.backend.banner.service;

import com.manara.backend.banner.dto.StudentBannerResponse;
import com.manara.backend.banner.mapper.BannerMapper;
import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.model.BannerDisplayFrequency;
import com.manara.backend.banner.model.BannerDismissal;
import com.manara.backend.banner.repository.BannerDismissalRepository;
import com.manara.backend.banner.repository.BannerRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What actually reaches a learner.
 *
 * <p>Two things are being checked here and they are different: that the right banners are chosen,
 * and that the ones chosen carry nothing the learner has no business seeing. The second is the one
 * a "just reuse the other DTO" change would break without failing anything else.
 */
@ExtendWith(MockitoExtension.class)
class StudentBannerServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 22, 12, 0);
    private static final Long STUDENT_ID = 20L;

    @Mock
    private BannerRepository bannerRepository;

    @Mock
    private BannerDismissalRepository bannerDismissalRepository;

    @Mock
    private StudentRepository studentRepository;

    private StudentBannerService studentBannerService;

    private final User studentUser = User.builder().id(2L).role(Role.STUDENT).build();
    private final User instructorUser = User.builder().id(3L).role(Role.INSTRUCTOR).build();
    private final Student student = Student.builder().id(STUDENT_ID).user(studentUser).build();
    private final Instructor owner = Instructor.builder().id(11L).user(instructorUser).build();

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        studentBannerService = new StudentBannerService(
                bannerRepository,
                bannerDismissalRepository,
                studentRepository,
                new BannerMapper(),
                new BannerSchedule(fixed));
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    @Test
    void nothingLiveMeansNoBannersAndNoDismissalLookup() {
        given(bannerRepository.findActive(NOW)).willReturn(List.of());

        assertThat(studentBannerService.getActiveBanners(studentUser)).isEmpty();
        verify(bannerDismissalRepository, never()).findDismissedBannerIds(any());
    }

    @Test
    void theOrderTheOwnersArrangedIsTheOrderTheLearnerGets() {
        givenStudent();
        given(bannerRepository.findActive(NOW))
                .willReturn(List.of(banner(1L), banner(2L), banner(3L)));
        given(bannerDismissalRepository.findDismissedBannerIds(STUDENT_ID)).willReturn(List.of());

        assertThat(studentBannerService.getActiveBanners(studentUser))
                .extracting("id").containsExactly(1L, 2L, 3L);
    }

    /** A refusal is permanent only for the mode that promised it would be. */
    @Test
    void aBannerThisLearnerDismissedForeverIsNotSentAgain() {
        givenStudent();
        Banner onceOnly = banner(1L);
        onceOnly.setDisplayFrequency(BannerDisplayFrequency.ONCE_PER_STUDENT);
        given(bannerRepository.findActive(NOW)).willReturn(List.of(onceOnly, banner(2L)));
        given(bannerDismissalRepository.findDismissedBannerIds(STUDENT_ID)).willReturn(List.of(1L));

        assertThat(studentBannerService.getActiveBanners(studentUser))
                .extracting("id").containsExactly(2L);
    }

    /**
     * The dismissal row belongs to one mode. A banner switched back to "every visit" comes back,
     * because that is what its owner is now asking for.
     */
    @Test
    void aDismissalDoesNotSuppressABannerThatIsNoLongerShownOnlyOnce() {
        givenStudent();
        Banner everyVisit = banner(1L);
        given(bannerRepository.findActive(NOW)).willReturn(List.of(everyVisit));
        given(bannerDismissalRepository.findDismissedBannerIds(STUDENT_ID)).willReturn(List.of(1L));

        assertThat(studentBannerService.getActiveBanners(studentUser))
                .extracting("id").containsExactly(1L);
    }

    /** An instructor looking at the learner side has no dismissals to subtract, not no access. */
    @Test
    void aViewerWhoIsNotALearnerStillSeesTheLiveBanners() {
        given(bannerRepository.findActive(NOW)).willReturn(List.of(banner(1L)));

        assertThat(studentBannerService.getActiveBanners(instructorUser)).hasSize(1);
        verify(bannerDismissalRepository, never()).findDismissedBannerIds(any());
    }

    // ── Shape ─────────────────────────────────────────────────────────────────

    /**
     * The internal name is what an instructor calls a banner among themselves, and the schedule and
     * position are how they file it. None of it is on the learner's response.
     */
    @Test
    void theLearnersResponseCarriesOnlyWhatTheCarouselRenders() {
        givenStudent();
        Banner live = banner(1L);
        live.setInternalName("عرض الصيف — النسخة الثالثة");
        given(bannerRepository.findActive(NOW)).willReturn(List.of(live));
        given(bannerDismissalRepository.findDismissedBannerIds(STUDENT_ID)).willReturn(List.of());

        var delivered = studentBannerService.getActiveBanners(studentUser).getFirst();

        assertThat(delivered.getId()).isEqualTo(1L);
        assertThat(delivered.getTitle()).isEqualTo("title-1");
        assertThat(delivered.isDismissible()).isTrue();
        assertThat(delivered.getDisplayFrequency()).isEqualTo(BannerDisplayFrequency.EVERY_VISIT);
        assertThat(StudentBannerResponse.class.getDeclaredFields())
                .extracting(Field::getName)
                .containsExactlyInAnyOrder("id", "title", "description", "imageUrl",
                        "callToActionLabel", "callToActionUrl", "dismissible", "displayFrequency");
    }

    // ── Dismissal ─────────────────────────────────────────────────────────────

    @Test
    void dismissingRecordsTheRefusalOnce() {
        givenStudent();
        Banner onceOnly = onceOnlyBanner();
        given(bannerRepository.findById(1L)).willReturn(Optional.of(onceOnly));
        given(bannerDismissalRepository.existsByBannerIdAndStudentId(1L, STUDENT_ID)).willReturn(false);

        studentBannerService.dismissBanner(studentUser, 1L);

        verify(bannerDismissalRepository).save(any(BannerDismissal.class));
    }

    /** Two devices, the same banner, the same fact. The second one writes nothing and is not an error. */
    @Test
    void dismissingTwiceIsAcceptedAndWritesNothingTheSecondTime() {
        givenStudent();
        given(bannerRepository.findById(1L)).willReturn(Optional.of(onceOnlyBanner()));
        given(bannerDismissalRepository.existsByBannerIdAndStudentId(1L, STUDENT_ID)).willReturn(true);

        studentBannerService.dismissBanner(studentUser, 1L);

        verify(bannerDismissalRepository, never()).save(any());
    }

    /** Hiding something the owner said could not be hidden is not the client's decision to make. */
    @Test
    void aBannerItsOwnerMarkedUndismissableCannotBeDismissed() {
        givenStudent();
        Banner undismissable = onceOnlyBanner();
        undismissable.setDismissible(false);
        given(bannerRepository.findById(1L)).willReturn(Optional.of(undismissable));

        assertThatThrownBy(() -> studentBannerService.dismissBanner(studentUser, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.notDismissible");
        verify(bannerDismissalRepository, never()).save(any());
    }

    /** "Once per session" that the server remembered would be "once, ever" from the first close. */
    @Test
    void aSessionScopedDismissalIsRefusedRatherThanMadePermanent() {
        givenStudent();
        given(bannerRepository.findById(1L)).willReturn(Optional.of(banner(1L)));

        assertThatThrownBy(() -> studentBannerService.dismissBanner(studentUser, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.dismissalNotPersisted");
        verify(bannerDismissalRepository, never()).save(any());
    }

    @Test
    void onlyALearnerCanDismissABanner() {
        assertThatThrownBy(() -> studentBannerService.dismissBanner(instructorUser, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.banner.onlyStudent");
    }

    @Test
    void dismissingABannerThatDoesNotExistIsNotFound() {
        givenStudent();
        given(bannerRepository.findById(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> studentBannerService.dismissBanner(studentUser, 404L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.banner.notFound");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private void givenStudent() {
        given(studentRepository.findByUserId(studentUser.getId())).willReturn(Optional.of(student));
    }

    private Banner onceOnlyBanner() {
        Banner banner = banner(1L);
        banner.setDisplayFrequency(BannerDisplayFrequency.ONCE_PER_STUDENT);
        return banner;
    }

    private Banner banner(Long id) {
        return Banner.builder()
                .id(id)
                .instructor(owner)
                .internalName("internal-" + id)
                .title("title-" + id)
                .timezone("Asia/Riyadh")
                .priority(id.intValue())
                .startAt(NOW.minusDays(1))
                .endAt(NOW.plusDays(10))
                .enabled(true)
                .dismissible(true)
                .displayFrequency(BannerDisplayFrequency.EVERY_VISIT)
                .draft(false)
                .build();
    }
}
