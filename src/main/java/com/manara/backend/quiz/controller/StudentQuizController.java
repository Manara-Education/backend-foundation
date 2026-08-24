package com.manara.backend.quiz.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.quiz.dto.QuizAttemptResponse;
import com.manara.backend.quiz.dto.QuizSubmissionRequest;
import com.manara.backend.quiz.service.QuizAttemptService;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Learner-facing quiz endpoints.
 *
 * <p>Scoped under the course on purpose: the course in the path is what the quiz is authorized
 * against, so a quiz can never be submitted through a course it does not belong to.
 */
@RestController
@RequestMapping("/api/v1/student/courses/{courseId}/quizzes")
@RequiredArgsConstructor
public class StudentQuizController {

    private final QuizAttemptService quizAttemptService;

    @PostMapping("/{quizId}/submit")
    public ApiResponse<QuizAttemptResponse> submit(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @PathVariable Long quizId,
            @RequestBody QuizSubmissionRequest request) {
        return ApiResponse.success(quizAttemptService.submit(user, courseId, quizId, request));
    }
}
