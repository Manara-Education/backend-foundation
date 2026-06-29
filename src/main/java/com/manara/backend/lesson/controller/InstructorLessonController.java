package com.manara.backend.lesson.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.service.LessonService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/instructor/courses/{courseId}/lessons")
@RequiredArgsConstructor
public class InstructorLessonController {

    private final LessonService lessonService;
    private final MessageService messageService;

    @GetMapping
    public ApiResponse<List<LessonResponse>> getCourseLessons(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        return ApiResponse.success(lessonService.getCourseLessons(user, courseId));
    }

    @PostMapping
    public ApiResponse<LessonResponse> addLesson(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody LessonRequest request) {
        return ApiResponse.success(lessonService.addLesson(user, courseId, request));
    }

    @PutMapping("/{lessonId}")
    public ApiResponse<LessonResponse> updateLesson(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @PathVariable Long lessonId,
            @Valid @RequestBody LessonRequest request) {
        return ApiResponse.success(lessonService.updateLesson(user, courseId, lessonId, request));
    }

    @DeleteMapping("/{lessonId}")
    public ApiResponse<MessageResponse> deleteLesson(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @PathVariable Long lessonId) {
        lessonService.deleteLesson(user, courseId, lessonId);
        return ApiResponse.success(MessageResponse.builder()
                .message(messageService.get("lesson.deleted"))
                .build());
    }
}
