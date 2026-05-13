package com.manara.backend.profile.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.profile.dto.InstructorPublicResponse;
import com.manara.backend.profile.service.InstructorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instructors")
@RequiredArgsConstructor
public class InstructorController {

    private final InstructorService instructorService;

    @GetMapping("/{instructorId}")
    public ApiResponse<InstructorPublicResponse> getInstructorProfile(@PathVariable Long instructorId) {
        return ApiResponse.success(instructorService.getInstructorProfile(instructorId));
    }

    @GetMapping("/{instructorId}/courses")
    public ApiResponse<List<CourseResponse>> getInstructorCourses(@PathVariable Long instructorId) {
        return ApiResponse.success(instructorService.getInstructorCourses(instructorId));
    }
}
