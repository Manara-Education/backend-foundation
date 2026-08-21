package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Everything the course editor needs to reconstruct its state, in one response.
 *
 * <p>Only the branch matching {@code structure} is populated: a {@code FLAT} course returns
 * {@code lessons} with an empty {@code modules}, a {@code MODULES} course the reverse. The API
 * never returns a mixed tree, whatever is still in the database.
 *
 * <p>Returned exclusively from instructor endpoints — it carries answer keys throughout.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorCourseResponse {

    private Long id;
    private String title;
    private String subtitle;
    private String image;
    private String description;
    private Integer duration;
    private Integer lessonCount;
    private Integer studentsCount;
    private Long instructorId;
    private String instructorName;

    private CourseStructure structure;
    private CourseStatus status;

    private List<InstructorLessonResponse> lessons;
    private List<InstructorModuleResponse> modules;
    private InstructorQuizResponse finalQuiz;

    private CourseAccessType accessType;
    private BigDecimal purchasePrice;

    /**
     * Mirror of {@code purchasePrice} under its former name, kept so existing clients of the
     * create/update responses keep reading a price.
     *
     * @deprecated use {@link #purchasePrice}
     */
    @Deprecated
    private BigDecimal price;

    private List<SubscriptionPlanResponse> subscriptionPlans;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
