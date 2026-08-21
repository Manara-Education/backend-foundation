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
 *
 * <p>The progression fields describe the viewing learner's own standing, so a client renders locks,
 * the progress bar and "continue where you left off" from what the server decided rather than from
 * rules of its own. They are absent for a viewer the course tracks no progress for.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseDetailsResponse {

    private CourseInfo course;
    private InstructorInfo instructor;

    /**
     * The viewing learner's own standing: enrolled, entitled, and — for a subscription — until when.
     *
     * <p>What the CTA should offer is read from here, not guessed from the pricing fields. "Every
     * lesson locked" describes a visitor who has not bought the course and a subscriber whose window
     * closed equally well; only this block separates them.
     */
    private CourseAccessResponse access;
    private CourseStructure structure;
    private List<LessonResponse> lessons;
    private List<LearnerModuleResponse> modules;
    private LearnerQuizResponse finalQuiz;

    /** Percentage of the course's lessons this learner has completed, 0-100. */
    private Integer progress;

    /** True once the curriculum is finished and the final exam, if there is one, is passed. */
    private Boolean courseCompleted;

    /** The lesson to open next, or {@code null} when nothing is left to open. */
    private Long nextLessonId;

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
