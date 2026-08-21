package com.manara.backend.banner.service;

import com.manara.backend.banner.dto.BannerOrderRequest;
import com.manara.backend.banner.dto.BannerRequest;
import com.manara.backend.banner.dto.BannerResponse;
import com.manara.backend.banner.mapper.BannerMapper;
import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.repository.BannerDismissalRepository;
import com.manara.backend.banner.repository.BannerRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Banner authoring, for the instructor who owns them.
 *
 * <p>Ownership is checked in exactly one place — {@link #requireOwnedBanner} — and every read and
 * write goes through it. A banner is only ever reached by an id the caller supplied, so a single
 * gate is the difference between "you may edit your banners" and "you may edit any banner whose id
 * you can guess".
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BannerService {

    private final BannerRepository bannerRepository;
    private final BannerDismissalRepository bannerDismissalRepository;
    private final InstructorRepository instructorRepository;
    private final BannerMapper bannerMapper;
    private final BannerValidator bannerValidator;
    private final BannerSchedule bannerSchedule;

    public List<BannerResponse> getMyBanners(User user) {
        var instructor = requireInstructor(user);
        return bannerRepository.findOwnedOrdered(instructor.getId()).stream()
                .map(this::toResponse)
                .toList();
    }

    public BannerResponse getBanner(User user, Long bannerId) {
        return toResponse(requireOwnedBanner(user, bannerId));
    }

    /**
     * A new banner joins the end of its owner's list. The management screen has no field for a
     * position — it is expressed by dragging rows — so appending is the only answer that does not
     * silently push somebody else's banner down.
     */
    @Transactional
    public BannerResponse createBanner(User user, BannerRequest request) {
        var instructor = requireInstructor(user);
        bannerValidator.validate(request);

        int priority = request.getPriority() != null
                ? request.getPriority()
                : bannerRepository.findHighestPriority(instructor.getId()) + 1;

        var banner = bannerRepository.save(bannerMapper.toBanner(request, instructor, priority));
        return toResponse(banner);
    }

    @Transactional
    public BannerResponse updateBanner(User user, Long bannerId, BannerRequest request) {
        var banner = requireOwnedBanner(user, bannerId);
        bannerValidator.validate(request);

        int priority = request.getPriority() != null ? request.getPriority() : banner.getPriority();
        bannerMapper.applyRequest(banner, request, priority);

        return toResponse(bannerRepository.save(banner));
    }

    /**
     * Deleting takes the learners' dismissals with it. They record a refusal of this banner and
     * nothing else, so leaving them behind would keep rows pointing at something that is gone.
     */
    @Transactional
    public void deleteBanner(User user, Long bannerId) {
        var banner = requireOwnedBanner(user, bannerId);
        bannerDismissalRepository.deleteByBannerId(banner.getId());
        bannerRepository.delete(banner);
    }

    /**
     * Rewrites the whole list's order from the ids it is given, in the order they are given.
     *
     * <p>A payload that names only some of them is accepted rather than refused: the client's list
     * can be a moment behind the database — a second tab, a banner created since the screen
     * loaded — and refusing the drag the owner just made because of that would be a worse answer
     * than honouring it and leaving the banners it did not mention below the ones it did.
     */
    @Transactional
    public List<BannerResponse> reorderBanners(User user, BannerOrderRequest request) {
        var instructor = requireInstructor(user);
        var owned = bannerRepository.findOwnedOrdered(instructor.getId());
        var byId = owned.stream().collect(Collectors.toMap(Banner::getId, Function.identity()));

        var requested = new LinkedHashSet<>(requireNoDuplicates(request.getBannerIds()));
        var ordered = new ArrayList<Banner>(owned.size());
        for (Long bannerId : requested) {
            Banner banner = byId.get(bannerId);
            if (banner == null) {
                throw new ResourceNotFoundException("error.banner.notFound", String.valueOf(bannerId));
            }
            ordered.add(banner);
        }
        Set<Long> placed = Set.copyOf(requested);
        owned.stream().filter(banner -> !placed.contains(banner.getId())).forEach(ordered::add);

        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setPriority(i + 1);
        }
        bannerRepository.saveAll(ordered);

        return ordered.stream().map(this::toResponse).toList();
    }

    private List<Long> requireNoDuplicates(List<Long> bannerIds) {
        Map<Long, Long> counts = bannerIds.stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .findFirst()
                .ifPresent(entry -> {
                    throw new BusinessException("error.banner.orderDuplicate", String.valueOf(entry.getKey()));
                });
        return bannerIds;
    }

    private BannerResponse toResponse(Banner banner) {
        return bannerMapper.toBannerResponse(banner, bannerSchedule.statusOf(banner));
    }

    private Instructor requireInstructor(User user) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.banner.onlyInstructor");
        }
        return instructorRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "error.profile.instructorNotFound", user.getId().toString()));
    }

    /**
     * The single ownership gate. Another instructor's banner is reported missing rather than
     * forbidden — its id is theirs, and confirming it exists tells a stranger something about it.
     */
    private Banner requireOwnedBanner(User user, Long bannerId) {
        var instructor = requireInstructor(user);
        return bannerRepository.findByIdAndInstructorId(bannerId, instructor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.banner.notFound", bannerId.toString()));
    }
}
