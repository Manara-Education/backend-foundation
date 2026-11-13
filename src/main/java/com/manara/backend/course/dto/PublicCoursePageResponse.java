package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One page of the public catalogue.
 *
 * <p>Paginated from the first release, rather than retrofitted, because this list is anonymous: an
 * unbounded response is something anybody can ask for, as often as they like. The counts come from
 * the same eligibility predicate as the items, so a draft or private course never occupies a slot,
 * a page or a place in the total.
 *
 * @param items      the courses on this page, newest first
 * @param page       zero-based page number, as requested
 * @param size       the page size used
 * @param totalItems how many eligible courses exist in total
 * @param totalPages how many pages of {@code size} that makes
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicCoursePageResponse(
        List<PublicCourseSummaryResponse> items,
        int page,
        int size,
        long totalItems,
        int totalPages) {
}
