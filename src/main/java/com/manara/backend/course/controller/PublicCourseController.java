package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.PublicCourseDetailResponse;
import com.manara.backend.course.dto.PublicCoursePageResponse;
import com.manara.backend.course.service.PublicCourseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.regex.Pattern;

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
    public ApiResponse<PublicCourseDetailResponse> getCourse(@PathVariable String courseId) {
        return ApiResponse.success(publicCourseService.getCourse(canonicalId(courseId)));
    }

    /**
     * The course id exactly as the list writes it, or a {@code 400}.
     *
     * <p>Read strictly rather than through Spring's number conversion, which decodes {@code 0x10} as
     * hexadecimal and accepts {@code +16} and {@code 016} — so one course answered at several
     * addresses. A public resource has one address. Every other spelling is refused as malformed,
     * the same way whatever it would have decoded to, before the database is asked anything.
     */
    private static Long canonicalId(String raw) {
        if (raw == null || !CANONICAL_ID.matcher(raw).matches()) {
            throw new BusinessException("error.request.parameterInvalid", "courseId");
        }
        try {
            return Long.valueOf(raw);
        } catch (NumberFormatException beyondLongRange) {
            throw new BusinessException("error.request.parameterInvalid", "courseId");
        }
    }

    /** A positive decimal integer with no sign, no leading zero and no other base. */
    private static final Pattern CANONICAL_ID = Pattern.compile("[1-9][0-9]{0,18}");
}
