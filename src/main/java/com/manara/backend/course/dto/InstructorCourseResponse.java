package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.CourseVisibility;
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
     * Who the course is offered to. The editor's own copy of the setting it just saved, and what
     * the instructor's course card renders its "private" marker from.
     *
     * <p>Beside {@code status}, not folded into it. The editor shows both, because "published" and
     * "private" are answers to different questions and an instructor needs to see both answers.
     */
    private CourseVisibility visibility;

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

    /**
     * The revision this editor model was read at. Echoed back as {@code expectedRevision} on save.
     *
     * <p>The whole of the editor's concurrency contract. An aggregate save is full replacement, so
     * the server has to be told which version of the course the payload was built from; it answers
     * every read <em>and</em> every accepted write with the current one, so an editor that keeps
     * adopting the value it was last given never conflicts with itself — including after a reorder,
     * which moves the revision like any other accepted change.
     */
    private Long revision;

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
