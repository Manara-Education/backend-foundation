package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One public course, as its anonymous detail page shows it: the summary plus the instructor's own
 * description of what the course delivers.
 *
 * <p>The same allowlist as {@link PublicCourseSummaryResponse}, with one more field. It is not the
 * learner's {@link CourseDetailsResponse} with sections removed. That shape exists to render a
 * curriculum, a viewer's access and their progress, and none of those belongs to somebody who has
 * not signed in.
 *
 * <p>{@code description} is plain text. Clients render it as text, never as HTML.
 *
 * @param id              the course id
 * @param title           the course title
 * @param subtitle        a short line under the title, or {@code null}
 * @param description     the instructor's description of the course, plain text, or {@code null}
 * @param imageUrl        the cover image, under the same rule as the summary, or {@code null}
 * @param instructorName  the teaching instructor's display name, or {@code null}
 * @param durationSeconds total video duration in seconds, or {@code null} when it is not known
 * @param lessonCount     how many lessons the course has
 * @param offer           what it costs, and whether that can be stated — identical to the
 *                        course's entry in the list
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicCourseDetailResponse(
        Long id,
        String title,
        String subtitle,
        String description,
        String imageUrl,
        String instructorName,
        Integer durationSeconds,
        Integer lessonCount,
        PublicCourseOfferResponse offer) {
}
