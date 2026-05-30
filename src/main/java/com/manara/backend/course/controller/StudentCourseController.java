package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.service.CourseService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/student/courses")
@RequiredArgsConstructor
public class StudentCourseController {

    private final CourseService courseService;

    @GetMapping("/{courseId}")
    public ApiResponse<CourseDetailsResponse> getCourseDetails(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @RequestParam CourseViewMode mode) {
        return ApiResponse.success(courseService.getCourseDetails(user, courseId, mode));
    }
}
