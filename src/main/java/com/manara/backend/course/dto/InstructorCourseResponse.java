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

    /**
     * Whether the course has changed in a way its learners should be told about.
     *
     * <p>The server's answer, derived from the publication baseline and the content version, so
     * every screen that shows an "Updated" badge shows the same thing. Deliberately not a pair of
     * timestamps for clients to compare: two clients comparing them would eventually disagree, and
     * the rule ("published, and edited since it was last published") belongs in one place.
     *
     * <p>False for a draft, false for a course that was never published, and false for every course
     * that already existed when this was introduced.
     */
    private Boolean hasUpdatesSincePublish;

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
