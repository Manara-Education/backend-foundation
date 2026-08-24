package com.manara.backend.lesson.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.lesson.dto.LessonCompletionResponse;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.service.LessonService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/student/courses/{courseId}/lessons")
@RequiredArgsConstructor
public class StudentLessonController {

    private final LessonService lessonService;

    @GetMapping("/{lessonId}")
    public ApiResponse<LessonDetailsResponse> getLesson(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @PathVariable Long lessonId) {
        return ApiResponse.success(lessonService.getLesson(user, courseId, lessonId));
    }

    /**
     * Returns the progression the completion produced rather than an acknowledgement, so the client
     * refreshes the curriculum from the server's answer instead of recomputing it.
     */
    @PostMapping("/{lessonId}/complete")
    public ApiResponse<LessonCompletionResponse> markLessonCompleted(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @PathVariable Long lessonId) {
        return ApiResponse.success(lessonService.markLessonCompleted(user, courseId, lessonId));
    }
}
