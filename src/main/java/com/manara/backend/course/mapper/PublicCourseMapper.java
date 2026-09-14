package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.PublicCourseDetailResponse;
import com.manara.backend.course.dto.PublicCourseOfferResponse;
import com.manara.backend.course.dto.PublicCoursePageResponse;
import com.manara.backend.course.dto.PublicCourseSummaryResponse;
import com.manara.backend.course.dto.PublicPricingStatus;
import com.manara.backend.course.dto.PublicSubscriptionPlanResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.service.PublicOffer;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the anonymous catalogue's shapes, field by field.
 *
 * <p>Every value a visitor receives is named in this class. Nothing is copied wholesale from an
 * entity or from another response type, so a field added to {@link Course} or to the signed-in
 * course shapes stays private until somebody adds it here on purpose.
 *
 * <p>What the course costs has already been decided by {@link PublicOffer} before it reaches this
 * class. The mapper only renders that decision.
 */
@Component
public class PublicCourseMapper {

    /**
     * What {@code FileUploadService} produces: {@code /uploads/} followed by a generated file name. A
     * relative path under the application's own public upload directory, and nothing else.
     */
    private static final Pattern UPLOAD_PATH = Pattern.compile("^/uploads/[A-Za-z0-9][A-Za-z0-9._-]{0,254}$");

    private static final int MAX_IMAGE_URL_LENGTH = 2048;

    public PublicCourseSummaryResponse toSummary(Course course, PublicOffer offer) {
        return new PublicCourseSummaryResponse(
                course.getId(),
                course.getTitle(),
                blankToNull(course.getSubtitle()),
                safeImageUrl(course.getImage()),
                instructorName(course),
                knownDuration(course.getDuration()),
                course.getLessonCount(),
                toOffer(offer));
    }

    public PublicCourseDetailResponse toDetail(Course course, PublicOffer offer) {
        return new PublicCourseDetailResponse(
                course.getId(),
                course.getTitle(),
                blankToNull(course.getSubtitle()),
                blankToNull(course.getDescription()),
                safeImageUrl(course.getImage()),
                instructorName(course),
                knownDuration(course.getDuration()),
                course.getLessonCount(),
                toOffer(offer));
    }

    public PublicCoursePageResponse toPage(List<PublicCourseSummaryResponse> items, int page, int size,
                                           long totalItems) {
        int totalPages = size <= 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, (totalItems + size - 1) / size);
        return new PublicCoursePageResponse(List.copyOf(items), page, size, totalItems, totalPages);
    }

    public PublicCourseOfferResponse toOffer(PublicOffer offer) {
        boolean free = offer.pricingStatus() == PublicPricingStatus.FREE;
        return new PublicCourseOfferResponse(
                offer.accessType(),
                offer.pricingStatus(),
                free ? null : PublicOffer.CURRENCY,
                offer.purchasePrice(),
                offer.plans().stream().map(this::toPlan).toList());
    }

    private PublicSubscriptionPlanResponse toPlan(SubscriptionPlan plan) {
        return new PublicSubscriptionPlanResponse(
                plan.getId(),
                plan.getName(),
                plan.getDuration(),
                plan.getUnit(),
                PublicOffer.money(plan.getPrice()));
    }

    /**
     * The cover image, if it is safe to hand to an anonymous page: a Manara upload path, or an
     * absolute {@code https} URL with a host and no credentials in it.
     *
     * <p>The image column is free text an instructor controls. Before this, only a signed-in
     * learner's page ever rendered it; a public page is somewhere a {@code javascript:} or
     * {@code data:} value, a plain-{@code http} tracker, or an address with credentials in it must not
     * be passed on to. Anything that is not clearly one of the two safe forms is dropped rather than
     * repaired, and the card falls back to having no image.
     */
    static String safeImageUrl(String image) {
        if (image == null) {
            return null;
        }
        String value = image.trim();
        if (value.isEmpty() || value.length() > MAX_IMAGE_URL_LENGTH) {
            return null;
        }
        if (UPLOAD_PATH.matcher(value).matches()) {
            return value.contains("..") ? null : value;
        }
        try {
            URI uri = new URI(value);
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            if (https && uri.getHost() != null && uri.getRawUserInfo() == null) {
                return uri.toASCIIString();
            }
        } catch (URISyntaxException ignored) {
            // Not a URL at all, so not something to render.
        }
        return null;
    }

    private static String instructorName(Course course) {
        var instructor = course.getInstructor();
        if (instructor == null || instructor.getUser() == null) {
            return null;
        }
        return blankToNull(instructor.getUser().getFullName());
    }

    /**
     * The course's total video duration in seconds, or {@code null} when it is not known.
     *
     * <p>The column is the sum of lesson durations, which the background video lookup fills in
     * second by second. Zero means no lookup has succeeded yet, not a course with no content, so it
     * is reported as unknown rather than as "0 minutes".
     */
    private static Integer knownDuration(Integer seconds) {
        return seconds == null || seconds <= 0 ? null : seconds;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
