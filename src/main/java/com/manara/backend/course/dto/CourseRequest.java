package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.EnumSet;
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

    /**
     * Accepted, and then ignored on update.
     *
     * <p>A course's duration is the sum of its lessons' durations, which only the video providers
     * know. The server recomputes it from the lessons after every content change and hands the
     * result back in the response — so the value a client sends is at best a stale echo of the
     * server's own figure, and honouring it would let a client that omitted the field wipe it.
     *
     * <p>{@code @PositiveOrZero}, not {@code @Positive}. A course whose videos have not been
     * measured yet legitimately has a duration of {@code 0} — every lesson starts there and stays
     * there until an out-of-band lookup lands. {@code @Positive} rejected exactly the value the API
     * had just returned, which meant a client that echoed the aggregate back verbatim, as the
     * editor does, was answered {@code 400} on every save. That made a course permanently
     * uneditable through its own editor.
     */
    @PositiveOrZero(message = "{validation.course.duration.positive}")
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
     * Which optional metadata fields the payload actually mentioned.
     *
     * <p>A Java bean cannot tell "absent" from "explicitly null" on its own, and for two of these
     * fields the difference matters a great deal: an update that says nothing about {@code image}
     * meant to leave the cover alone, and clearing it instead is how a metadata-only save blanked
     * a published course's thumbnail. Recording presence in the setters — which Jackson calls only
     * for keys that are in the JSON — is what separates the two without changing the wire format
     * or adding a dependency.
     *
     * <p>The fields that are not tracked do not need to be: {@code title} and {@code description}
     * are required, and {@code status}, {@code structure}, {@code accessType}, the prices, the
     * plans and the content tree all already read {@code null} as "leave it alone" in
     * {@code CourseValidator} and {@code CourseContentSynchronizer}.
     */
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private EnumSet<Field> presentFields;

    /** The optional metadata fields whose presence is tracked. */
    public enum Field {
        SUBTITLE, IMAGE, DURATION
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
        this.presentFields = mark(this.presentFields, Field.SUBTITLE);
    }

    public void setImage(String image) {
        this.image = image;
        this.presentFields = mark(this.presentFields, Field.IMAGE);
    }

    public void setDuration(Integer duration) {
        this.duration = duration;
        this.presentFields = mark(this.presentFields, Field.DURATION);
    }

    /** Whether the payload mentioned this field at all, whatever value it gave it. */
    public boolean carries(Field field) {
        return presentFields != null && presentFields.contains(field);
    }

    private static EnumSet<Field> mark(EnumSet<Field> fields, Field field) {
        EnumSet<Field> next = fields == null ? EnumSet.noneOf(Field.class) : fields;
        next.add(field);
        return next;
    }

    /**
     * The generated builder, with the three tracked fields taught to record themselves.
     *
     * <p>Lombok's builder assigns fields directly rather than through setters, so without these a
     * request built in Java — every test, and any future server-side caller — would look as though
     * it had mentioned nothing. Hand-writing the three suppresses Lombok's versions and leaves the
     * rest of the builder generated as before.
     */
    public static class CourseRequestBuilder {

        public CourseRequestBuilder subtitle(String subtitle) {
            this.subtitle = subtitle;
            this.presentFields = mark(this.presentFields, Field.SUBTITLE);
            return this;
        }

        public CourseRequestBuilder image(String image) {
            this.image = image;
            this.presentFields = mark(this.presentFields, Field.IMAGE);
            return this;
        }

        public CourseRequestBuilder duration(Integer duration) {
            this.duration = duration;
            this.presentFields = mark(this.presentFields, Field.DURATION);
            return this;
        }
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
