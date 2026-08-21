package com.manara.backend.banner.service;

import com.manara.backend.banner.dto.StudentBannerResponse;
import com.manara.backend.banner.mapper.BannerMapper;
import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.model.BannerDisplayFrequency;
import com.manara.backend.banner.repository.BannerDismissalRepository;
import com.manara.backend.banner.repository.BannerRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Banner delivery, for the learner being shown them.
 *
 * <p>What reaches a learner is decided entirely here, and it is decided by subtraction: start from
 * the banners that are live right now, take away the ones this learner has permanently refused, and
 * describe what is left in the smallest shape the carousel can render.
 *
 * <p>Only {@link BannerDisplayFrequency#ONCE_PER_STUDENT} dismissals are the server's business. The
 * other two modes are scoped to a visit — re-showing them next time is what they mean — and asking
 * the server to remember something it is meant to forget would turn "once per session" into
 * "once, ever" the first time a learner closed one.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentBannerService {

    private final BannerRepository bannerRepository;
    private final BannerDismissalRepository bannerDismissalRepository;
    private final StudentRepository studentRepository;
    private final BannerMapper bannerMapper;
    private final BannerSchedule bannerSchedule;

    /**
     * The banners this learner should be shown now, in the order their owners arranged them.
     *
     * <p>Ordering is the repository's, not the database's: lowest priority first, then the earlier
     * start, then the id. The last one is there so two banners an instructor never distinguished
     * still come back in the same order on every request.
     */
    public List<StudentBannerResponse> getActiveBanners(User user) {
        var active = bannerRepository.findActive(bannerSchedule.now());
        if (active.isEmpty()) {
            return List.of();
        }

        Set<Long> dismissed = findStudent(user)
                .map(student -> Set.copyOf(bannerDismissalRepository.findDismissedBannerIds(student.getId())))
                .orElse(Set.of());

        return active.stream()
                .filter(banner -> !isPermanentlyDismissed(banner, dismissed))
                .map(bannerMapper::toStudentBannerResponse)
                .toList();
    }

    /**
     * Records that this learner will not be shown this banner again.
     *
     * <p>Repeating it is not an error — a learner on two devices can dismiss the same banner
     * twice, and the second one is the same fact as the first, so it is accepted and nothing is
     * written. A banner the owner did not mark dismissible is refused: hiding something they said
     * could not be hidden is a decision the client does not get to make.
     */
    @Transactional
    public void dismissBanner(User user, Long bannerId) {
        var student = requireStudent(user);
        var banner = bannerRepository.findById(bannerId)
                .orElseThrow(() -> new ResourceNotFoundException("error.banner.notFound", bannerId.toString()));

        if (!banner.isDismissible()) {
            throw new BusinessException("error.banner.notDismissible");
        }
        if (banner.getDisplayFrequency() != BannerDisplayFrequency.ONCE_PER_STUDENT) {
            throw new BusinessException("error.banner.dismissalNotPersisted");
        }
        if (bannerDismissalRepository.existsByBannerIdAndStudentId(banner.getId(), student.getId())) {
            return;
        }
        bannerDismissalRepository.save(bannerMapper.toBannerDismissal(banner, student));
    }

    private boolean isPermanentlyDismissed(Banner banner, Set<Long> dismissed) {
        return banner.getDisplayFrequency() == BannerDisplayFrequency.ONCE_PER_STUDENT
                && dismissed.contains(banner.getId());
    }

    /**
     * A viewer who is not a learner — an instructor looking at the student side — has no dismissals
     * to subtract. That is an empty set, not a refusal: the list itself is public to any signed-in
     * user, and only its filtering is personal.
     */
    private Optional<Student> findStudent(User user) {
        if (user == null || user.getRole() != Role.STUDENT) {
            return Optional.empty();
        }
        return studentRepository.findByUserId(user.getId());
    }

    private Student requireStudent(User user) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.banner.onlyStudent");
        }
        return studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "error.profile.studentNotFound", user.getId().toString()));
    }
}
