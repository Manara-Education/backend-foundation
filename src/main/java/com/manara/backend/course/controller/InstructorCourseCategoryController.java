package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.CourseCategoryResponse;
import com.manara.backend.course.service.CourseCategoryService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instructor/course-categories")
@RequiredArgsConstructor
public class InstructorCourseCategoryController {

    private final CourseCategoryService courseCategoryService;

    @GetMapping
    public ApiResponse<List<CourseCategoryResponse>> list(@AuthenticationPrincipal User user) {
        return ApiResponse.success(courseCategoryService.listForInstructor(user));
    }
}
