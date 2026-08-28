package com.manara.backend.course.dto;

import com.manara.backend.common.json.Patch;
import com.manara.backend.common.json.PatchDeserializer;
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
import tools.jackson.databind.annotation.JsonDeserialize;

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
 *
 * <h2>Full replacement is not last-write-wins</h2>
 * Because the payload is the whole course, a copy of it built an hour ago is an hour-old course. An
 * update therefore has to say which revision of the server's course it was built from — see
 * {@link #expectedRevision} — and is refused rather than applied if that is no longer the current
 * one. Without it, correcting a typo in one tab silently restored every other field to whatever
 * that tab had loaded, including the price.
 *
 * <p>The constructor is deliberately private. Jackson picks a properties-based creator over the
 * no-arg constructor when it can see one, and which of the two it picks used to decide whether
 * this DTO worked at all; hiding it removes the choice. The presence-aware fields below are
 * correct either way regardless, and that is the actual guarantee — this only removes the
 * ambiguity.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor
public class CourseRequest {

    @NotBlank(message = "{validation.course.title.required}")
    private String title;

    /**
     * Optional metadata, and one of the two fields where "absent" and "null" mean different things.
     *
     * <p>Typed as a {@link Patch} rather than a {@code String} for exactly that reason: a save that
     * says nothing about the subtitle must leave it alone, and one that sends {@code null} must
     * clear it. See {@link Patch} for why the distinction lives in the type instead of in the
     * setters.
     */
    @JsonDeserialize(using = PatchDeserializer.class)
    private Patch<String> subtitle;

    /** The cover image URL. Presence-tracked for the same reason as {@link #subtitle}. */
    @JsonDeserialize(using = PatchDeserializer.class)
    private Patch<String> image;

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

    /**
     * The course revision this payload was built from, as the server last reported it.
     *
     * <p>Required on update, absent on create. The server compares it against the course's current
     * revision under a row lock and refuses the save if they differ, so an aggregate assembled from
     * a copy someone else has since edited writes nothing at all rather than restoring every field
     * it is holding. The value is server-generated and echoed back unchanged; a client never
     * computes one.
     *
     * @see com.manara.backend.course.model.Course#getRevision()
     */
    private Long expectedRevision;

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

    /** Whether the payload mentioned {@code subtitle}, whatever value it gave it. */
    public boolean carriesSubtitle() {
        return Patch.isPresent(subtitle);
    }

    /** The subtitle the payload asked for, or {@code null} when it never mentioned one. */
    public String subtitleValue() {
        return Patch.valueOf(subtitle);
    }

    /** Whether the payload mentioned {@code image}, whatever value it gave it. */
    public boolean carriesImage() {
        return Patch.isPresent(image);
    }

    /** The cover the payload asked for, or {@code null} when it never mentioned one. */
    public String imageValue() {
        return Patch.valueOf(image);
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

    /**
     * The builder, with the two presence-tracked fields taking their plain value.
     *
     * <p>{@code subtitle("x")} reads as "the payload said x" and {@code subtitle(null)} as "the
     * payload said null", which are the two states a client can express — and not calling it at all
     * is absence, exactly as omitting the key is on the wire. Deliberately only this overload: a
     * second one taking a {@link Patch} would make {@code image(null)} ambiguous, which is the one
     * call that most needs to mean something definite.
     */
    public static class CourseRequestBuilder {

        public CourseRequestBuilder subtitle(String subtitle) {
            this.subtitle = Patch.of(subtitle);
            return this;
        }

        public CourseRequestBuilder image(String image) {
            this.image = Patch.of(image);
            return this;
        }
    }
}
