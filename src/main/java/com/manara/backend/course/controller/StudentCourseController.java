package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.CheckoutResponse;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.service.CourseCheckoutService;
import com.manara.backend.course.service.CourseService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/student/courses")
@RequiredArgsConstructor
public class StudentCourseController {

    private final CourseService courseService;
    private final CourseCheckoutService courseCheckoutService;

    @GetMapping
    public ApiResponse<List<CourseResponse>> getAllCourses() {
        return ApiResponse.success(courseService.getPublishedCourses());
    }

    @GetMapping("/{courseId}")
    public ApiResponse<CourseDetailsResponse> getCourseDetails(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @RequestParam CourseViewMode mode) {
        return ApiResponse.success(courseService.getCourseDetails(user, courseId, mode));
    }

    /**
     * The one way a learner gains access to a course, whichever of the three ways it is sold.
     *
     * <p>The body is shaped by the course, not chosen by the caller: a free course takes {@code {}},
     * a purchase takes a {@code paymentMethod}, a subscription takes a {@code planId} as well. What
     * gets charged is decided from the course's and the plan's own stored figures.
     *
     * <p>Safe to repeat. A checkout that already granted access answers with the same body and takes
     * no further payment.
     */
    @PostMapping("/{courseId}/checkout")
    public ApiResponse<CheckoutResponse> checkout(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody(required = false) CheckoutRequest request) {
        return ApiResponse.success(courseCheckoutService.checkout(user, courseId, request));
    }
}
