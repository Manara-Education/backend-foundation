package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Learner-facing course details.
 *
 * <p>Like the editor response, only the branch matching {@code structure} is populated: a flat
 * course fills {@code lessons}, a module course fills {@code modules}. Every quiz in the tree is
 * the learner view, which has no answer key.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseDetailsResponse {

    private CourseInfo course;
    private InstructorInfo instructor;
    private CourseStructure structure;
    private List<LessonResponse> lessons;
    private List<LearnerModuleResponse> modules;
    private LearnerQuizResponse finalQuiz;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class CourseInfo {
        private Long id;
        private String title;
        private String subtitle;
        private String image;
        private String description;
        private String duration;
        private String remainingDuration;
        private Integer lessonCount;
        private BigDecimal price;
        private BigDecimal purchasePrice;
        private CourseAccessType accessType;
        private List<SubscriptionPlanResponse> subscriptionPlans;
        private Integer studentsCount;
        private LocalDateTime createdAt;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class InstructorInfo {
        private Long id;
        private String fullName;
        private String email;
        private String bio;
        private String specialization;
    }
}
