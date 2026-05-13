package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.EnrollmentResponse;
import com.manara.backend.course.service.CourseService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/courses")
@RequiredArgsConstructor
public class CourseController {

    private final CourseService courseService;

    @GetMapping
    public ApiResponse<List<CourseResponse>> getAllCourses() {
        return ApiResponse.success(courseService.getAllCourses());
    }

    @GetMapping("/{courseId}")
    public ApiResponse<CourseResponse> getCourseById(@PathVariable Long courseId) {
        return ApiResponse.success(courseService.getCourseById(courseId));
    }

    @GetMapping("/my-courses")
    public ApiResponse<List<CourseResponse>> getMyCourses(@AuthenticationPrincipal User user) {
        return ApiResponse.success(courseService.getMyCourses(user));
    }

    @PostMapping
    public ApiResponse<CourseResponse> createCourse(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.createCourse(user, request));
    }

    @PutMapping("/{courseId}")
    public ApiResponse<CourseResponse> updateCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.updateCourse(user, courseId, request));
    }

    @DeleteMapping("/{courseId}")
    public ApiResponse<MessageResponse> deleteCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        courseService.deleteCourse(user, courseId);
        return ApiResponse.success(MessageResponse.builder().message("Course deleted successfully").build());
    }

    @PostMapping("/{courseId}/enroll")
    public ApiResponse<EnrollmentResponse> enrollInCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        return ApiResponse.success(courseService.enrollInCourse(user, courseId));
    }

    @GetMapping("/my-enrollments")
    public ApiResponse<List<EnrollmentResponse>> getMyEnrollments(
            @AuthenticationPrincipal User user) {
        return ApiResponse.success(courseService.getMyEnrollments(user));
    }
}
