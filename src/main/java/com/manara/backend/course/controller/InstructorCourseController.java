package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.service.CourseService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Course authoring. Create, read and update all speak the same full aggregate — content tree,
 * exams, pricing and status in one payload — so the editor never has to stitch several calls
 * together, and no scoped endpoint can drift away from the shared domain rules.
 */
@RestController
@RequestMapping("/api/v1/instructor/courses")
@RequiredArgsConstructor
public class InstructorCourseController {

    private final CourseService courseService;

    @GetMapping
    public ApiResponse<List<CourseResponse>> getAllCourses() {
        return ApiResponse.success(courseService.getAllCourses());
    }

    @GetMapping("/my-courses")
    public ApiResponse<List<CourseResponse>> getMyCourses(@AuthenticationPrincipal User user) {
        return ApiResponse.success(courseService.getMyCourses(user));
    }

    @GetMapping("/{courseId}")
    public ApiResponse<InstructorCourseResponse> getCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        return ApiResponse.success(courseService.getCourseForEditing(user, courseId));
    }

    @PostMapping
    public ApiResponse<InstructorCourseResponse> createCourse(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.createCourse(user, request));
    }

    @PutMapping("/{courseId}")
    public ApiResponse<InstructorCourseResponse> updateCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.updateCourse(user, courseId, request));
    }
}
