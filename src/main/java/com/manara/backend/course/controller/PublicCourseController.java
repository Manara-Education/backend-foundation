package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.PublicCourseDetailResponse;
import com.manara.backend.course.dto.PublicCoursePageResponse;
import com.manara.backend.course.service.PublicCourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public catalogue, readable without an account.
 *
 * <p>Read-only and deliberately small: two {@code GET}s, contributed to the security policy one path
 * at a time by {@code PublicCourseSecurityConfig}. Browsing is anonymous; enrolling and paying are
 * not, and none of those routes are here. The contract is {@code docs/api/PUBLIC_COURSE_API.md}.
 */
@RestController
@RequestMapping("/api/v1/public/courses")
@RequiredArgsConstructor
public class PublicCourseController {

    private final PublicCourseService publicCourseService;

    /**
     * A page of the courses anyone may see, newest first.
     *
     * <p>{@code page} is zero-based; {@code size} is 1 to {@value PublicCourseService#MAX_PAGE_SIZE}.
     * There is no sort parameter — the order is the server's, so pages are stable.
     */
    @GetMapping
    public ApiResponse<PublicCoursePageResponse> listCourses(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.success(publicCourseService.listCourses(page, size));
    }

    /** One course anyone may see; any other id is a {@code 404} that says nothing about why. */
    @GetMapping("/{courseId}")
    public ApiResponse<PublicCourseDetailResponse> getCourse(@PathVariable Long courseId) {
        return ApiResponse.success(publicCourseService.getCourse(courseId));
    }
}
