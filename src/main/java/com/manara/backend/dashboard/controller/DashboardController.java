package com.manara.backend.dashboard.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.dashboard.dto.CourseViewResponse;
import com.manara.backend.dashboard.service.DashboardService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/student")
    public ApiResponse<List<CourseViewResponse>> getStudentCourses(@AuthenticationPrincipal User user) {
        return ApiResponse.success(dashboardService.getStudentCourses(user));
    }
}
