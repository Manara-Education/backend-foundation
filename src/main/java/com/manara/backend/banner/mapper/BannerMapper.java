package com.manara.backend.banner.mapper;

import com.manara.backend.banner.dto.BannerRequest;
import com.manara.backend.banner.dto.BannerResponse;
import com.manara.backend.banner.dto.StudentBannerResponse;
import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.model.BannerDisplayFrequency;
import com.manara.backend.banner.model.BannerDismissal;
import com.manara.backend.banner.model.BannerStatus;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import org.springframework.stereotype.Component;

/**
 * The only place a banner entity or a banner DTO is built.
 *
 * <p>Two response shapes on purpose. {@link #toBannerResponse} answers the owner and carries
 * everything they authored; {@link #toStudentBannerResponse} answers a learner and is built field by
 * field from scratch, so nothing added to the entity later can leak into it by simply existing.
 */
@Component
public class BannerMapper {

    /** The zone the management form offers first, used when a payload does not name one. */
    private static final String DEFAULT_TIMEZONE = "Asia/Riyadh";

    public Banner toBanner(BannerRequest request, Instructor instructor, int priority) {
        return Banner.builder()
                .instructor(instructor)
                .internalName(request.getInternalName().trim())
                .title(request.getTitle().trim())
                .description(trimToNull(request.getDescription()))
                .imageUrl(trimToNull(request.getImageUrl()))
                .callToActionLabel(trimToNull(request.getCallToActionLabel()))
                .callToActionUrl(trimToNull(request.getCallToActionUrl()))
                .startAt(request.getStartAt())
                .endAt(request.getEndAt())
                .timezone(resolveTimezone(request))
                .priority(priority)
                .enabled(resolveEnabled(request))
                .dismissible(!Boolean.FALSE.equals(request.getDismissible()))
                .displayFrequency(resolveFrequency(request))
                .draft(Boolean.TRUE.equals(request.getDraft()))
                .build();
    }

    /**
     * Update is full replacement, so every field is written — including the optional ones the
     * payload left out, which is how the editor clears a description or removes an image.
     */
    public void applyRequest(Banner banner, BannerRequest request, int priority) {
        banner.setInternalName(request.getInternalName().trim());
        banner.setTitle(request.getTitle().trim());
        banner.setDescription(trimToNull(request.getDescription()));
        banner.setImageUrl(trimToNull(request.getImageUrl()));
        banner.setCallToActionLabel(trimToNull(request.getCallToActionLabel()));
        banner.setCallToActionUrl(trimToNull(request.getCallToActionUrl()));
        banner.setStartAt(request.getStartAt());
        banner.setEndAt(request.getEndAt());
        banner.setTimezone(resolveTimezone(request));
        banner.setPriority(priority);
        banner.setEnabled(resolveEnabled(request));
        banner.setDismissible(!Boolean.FALSE.equals(request.getDismissible()));
        banner.setDisplayFrequency(resolveFrequency(request));
        banner.setDraft(Boolean.TRUE.equals(request.getDraft()));
    }

    public BannerDismissal toBannerDismissal(Banner banner, Student student) {
        return BannerDismissal.builder()
                .banner(banner)
                .student(student)
                .build();
    }

    public BannerResponse toBannerResponse(Banner banner, BannerStatus status) {
        return BannerResponse.builder()
                .id(banner.getId())
                .internalName(banner.getInternalName())
                .title(banner.getTitle())
                .description(banner.getDescription())
                .imageUrl(banner.getImageUrl())
                .callToActionLabel(banner.getCallToActionLabel())
                .callToActionUrl(banner.getCallToActionUrl())
                .startAt(banner.getStartAt())
                .endAt(banner.getEndAt())
                .timezone(banner.getTimezone())
                .priority(banner.getPriority())
                .enabled(banner.isEnabled())
                .dismissible(banner.isDismissible())
                .displayFrequency(banner.getDisplayFrequency())
                .draft(banner.isDraft())
                .status(status)
                .createdAt(banner.getCreatedAt())
                .updatedAt(banner.getUpdatedAt())
                .build();
    }

    public StudentBannerResponse toStudentBannerResponse(Banner banner) {
        return StudentBannerResponse.builder()
                .id(banner.getId())
                .title(banner.getTitle())
                .description(banner.getDescription())
                .imageUrl(banner.getImageUrl())
                .callToActionLabel(banner.getCallToActionLabel())
                .callToActionUrl(banner.getCallToActionUrl())
                .dismissible(banner.isDismissible())
                .displayFrequency(banner.getDisplayFrequency())
                .build();
    }

    /** A draft is never live, so saving one as a draft also switches it off. */
    private boolean resolveEnabled(BannerRequest request) {
        if (Boolean.TRUE.equals(request.getDraft())) {
            return false;
        }
        return !Boolean.FALSE.equals(request.getEnabled());
    }

    private String resolveTimezone(BannerRequest request) {
        return isBlank(request.getTimezone()) ? DEFAULT_TIMEZONE : request.getTimezone().trim();
    }

    private BannerDisplayFrequency resolveFrequency(BannerRequest request) {
        return request.getDisplayFrequency() == null
                ? BannerDisplayFrequency.EVERY_VISIT
                : request.getDisplayFrequency();
    }

    private String trimToNull(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
