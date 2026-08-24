package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * The full course aggregate as submitted by the editor: metadata, content tree, exams and pricing.
 *
 * <p>Semantics are full replacement. Whatever the payload contains becomes the course; nested
 * children carrying an {@code id} are updated in place, children without one are created, and
 * children the payload no longer mentions are removed. A {@code null} or omitted quiz means the
 * owner has no quiz.
 *
 * <p>{@code structure} decides which of {@code lessons} and {@code modules} is read — the other is
 * ignored on write and emptied on read, so the two can never be active at once.
 *
 * <p>Cross-field rules (pricing, structure, publishing, quizzes) live in {@code CourseValidator}
 * and {@code QuizValidator} rather than in annotations, because they depend on each other.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseRequest {

    @NotBlank(message = "{validation.course.title.required}")
    private String title;

    private String subtitle;

    private String image;

    @NotBlank(message = "{validation.course.description.required}")
    private String description;

    @Positive(message = "{validation.course.duration.positive}")
    private Integer duration;

    /** Defaults to {@link CourseStructure#FLAT} when omitted, matching pre-existing courses. */
    private CourseStructure structure;

    /** Read only when {@code structure} is {@code FLAT}. */
    private List<LessonRequest> lessons;

    /** Read only when {@code structure} is {@code MODULES}. */
    private List<ModuleRequest> modules;

    /** The course final exam. Optional — {@code null} removes it. */
    private QuizRequest finalQuiz;

    /**
     * Defaults to {@link CourseAccessType#PURCHASE} when omitted with a positive price and to
     * {@link CourseAccessType#FREE} otherwise, so clients written against the previous
     * price-only contract keep working.
     */
    private CourseAccessType accessType;

    private BigDecimal purchasePrice;

    /**
     * Former name of {@code purchasePrice}. Still accepted so existing clients are not broken;
     * {@code purchasePrice} wins when both are present.
     *
     * @deprecated use {@link #purchasePrice}
     */
    @Deprecated
    private BigDecimal price;

    private List<SubscriptionPlanRequest> subscriptionPlans;

    /** Defaults to {@link CourseStatus#DRAFT} on create and to the course's current status on update. */
    private CourseStatus status;

    /**
     * The one-off price to store, preferring the current field name over the legacy one.
     */
    public BigDecimal resolvePurchasePrice() {
        return purchasePrice != null ? purchasePrice : price;
    }

    /**
     * Whether this payload carries the content tree for the given structure at all.
     *
     * <p>An omitted collection is a metadata-only update and leaves the content untouched; an empty
     * one is an explicit "remove everything". Keeping the two apart is what stops a client that
     * only wanted to rename a course from deleting all of its lessons.
     */
    public boolean carriesContentFor(CourseStructure structure) {
        return structure == CourseStructure.MODULES ? modules != null : lessons != null;
    }
}
