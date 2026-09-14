package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A course card in the public catalogue: what an anonymous visitor may know about an offer before
 * signing in.
 *
 * <p>An allowlist, and deliberately a new type rather than {@link CourseResponse} with fields
 * blanked out. {@code CourseResponse} is the authenticated catalogue's shape, and it carries
 * publication state, visibility, the instructor's internal id, learner counts and — through
 * {@code lessons} — room for curriculum content. Reusing it would make every field added to the
 * signed-in shape public by default. Here a field is public only because it is written below.
 *
 * <p>Never carried: lessons, videos or media URLs, quiz content, learner counts or progress,
 * publication or visibility state (every course here is published and public by construction),
 * internal instructor or user ids, and anything about the person asking.
 *
 * @param id              the course id — also the detail route's path segment
 * @param title           the course title
 * @param subtitle        a short line under the title, or {@code null}
 * @param imageUrl        the cover image, only when it is a Manara upload path ({@code /uploads/...})
 *                        or an {@code https} URL; otherwise {@code null}
 * @param instructorName  the teaching instructor's display name, as learners already see it, or
 *                        {@code null}
 * @param durationSeconds total video duration in seconds, or {@code null} when it is not known
 * @param lessonCount     how many lessons the course has
 * @param offer           what it costs, and whether that can be stated
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicCourseSummaryResponse(
        Long id,
        String title,
        String subtitle,
        String imageUrl,
        String instructorName,
        Integer durationSeconds,
        Integer lessonCount,
        PublicCourseOfferResponse offer) {
}
