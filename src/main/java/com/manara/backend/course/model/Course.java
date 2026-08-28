package com.manara.backend.course.model;

import com.manara.backend.profile.model.Instructor;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * Updates name only the columns that actually changed.
 *
 * <p>Not a performance tweak — a correctness one, and the focused ordering commands do not work
 * without it. Hibernate's default UPDATE lists every column, so a command that changes one field
 * also writes back every other field as its own transaction happened to read them. A reorder that
 * began before a rename committed would therefore undo the rename on the way out: it never touched
 * the title, but it wrote one. Which defeats the entire reason the reorder is a focused command
 * rather than an aggregate save.
 */
@Entity
@DynamicUpdate
@Table(name = "courses")
public class Course implements TrackedContent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    private String image;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Estimated total duration in minutes
    private Integer duration;

    @org.hibernate.annotations.Formula("(SELECT COUNT(l.id) FROM lessons l WHERE l.course_id = id)")
    private Integer lessonCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id", nullable = false)
    private Instructor instructor;

    /**
     * Decides whether lessons hang off the course directly or off its modules. Existing rows
     * predate the field, so the column default backfills them as {@link CourseStructure#FLAT} —
     * which is exactly what they are.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'FLAT'")
    @Column(nullable = false, length = 20)
    private CourseStructure structure = CourseStructure.FLAT;

    /**
     * New courses start as drafts. The column default is {@code PUBLISHED} on purpose: rows that
     * already existed when this column was introduced were live and visible to learners, and
     * silently unpublishing them would be a regression. New rows always carry the Java default
     * because Hibernate includes the column in every insert.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PUBLISHED'")
    @Column(nullable = false, length = 20)
    private CourseStatus status = CourseStatus.DRAFT;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'FREE'")
    @Column(name = "access_type", nullable = false, length = 20)
    private CourseAccessType accessType = CourseAccessType.FREE;

    /**
     * One-off purchase price. Kept on the original {@code price} column so existing data, the
     * checkout flow and existing API consumers keep working — this is the same pricing concept,
     * renamed, not a second one.
     */
    @Column(name = "price")
    private BigDecimal purchasePrice;

    @Builder.Default
    @Column(name = "students_count", nullable = false)
    private Integer studentsCount = 0;

    /**
     * When this course last became publicly visible, or {@code null} if it never has.
     *
     * <p>The baseline {@link #hasUpdatesSincePublish()} is measured against. Moved only by
     * {@link #markPublished(LocalDateTime)} — publishing is the only thing that defines a new
     * version of a course as far as its learners are concerned.
     */
    @Column(name = "last_published_at")
    private LocalDateTime lastPublishedAt;

    /**
     * When an instructor last changed something a learner can see.
     *
     * <p>Deliberately not {@link #updatedAt}. That column is Hibernate's {@code @PreUpdate} stamp
     * and it moves for reasons that have nothing to do with authoring: {@code CheckoutProcessor}
     * increments {@link #studentsCount} on every purchase, and {@code VideoMetadataService}
     * rewrites {@link #duration} from a background thread once a video lookup lands. A learner
     * badge driven by {@code updatedAt} would light up because somebody else bought the course.
     *
     * <p>Moved only by {@link #markContentChanged(LocalDateTime)}, which the authoring services
     * call once per request and only when something actually changed.
     *
     * <p>Two rules are read from it, and they are different questions. {@code hasUpdatesSincePublish}
     * compares it to {@link #lastPublishedAt} and answers the instructor's — "have I edited since I
     * published?". {@code CourseUpdateResolver} compares it to an {@link Enrollment}'s
     * {@code enrolledAt} and answers the learner's — "has this changed since I joined?" — which is
     * per-enrollment and can differ between two students of the same course.
     *
     * <p>Still nullable, even though {@link #onCreate()} now defaults it. Making the column
     * {@code NOT NULL} would break a rolling deploy: an instance running the previous build inserts
     * a course with this column explicitly null and stamps it a statement later.
     */
    @Column(name = "content_updated_at")
    private LocalDateTime contentUpdatedAt;

    /**
     * How many accepted edits this course aggregate has had. The optimistic-concurrency token.
     *
     * <p>The aggregate {@code PUT} is full replacement, so a payload built from a copy of the
     * course loaded an hour ago <em>is</em> an hour-old course — saving it restored every field the
     * client was holding, silently reverting whatever anyone else had changed in the meantime. A
     * paid course went back to free because somebody renamed a lesson in another tab, and both
     * requests were answered {@code 200}. An update therefore has to say which revision it was
     * built from, and this is what it names.
     *
     * <p>Not a JPA {@code @Version}, and the difference is the whole point. {@code @Version} guards
     * one row against two persistence contexts holding it at once; every request here loads the
     * course fresh and then applies an old <em>client</em> aggregate on top, which no row-level
     * check can see. This is incremented by the domain — once per accepted request that changed
     * anything, curriculum or commerce, whichever endpoint made the change — so it describes the
     * whole aggregate a client edits, not just the {@code courses} row.
     *
     * <p>Column default {@code 0} so existing rows and any instance still running the previous
     * build both read as a real revision rather than null.
     */
    @Builder.Default
    @ColumnDefault("0")
    @Column(nullable = false)
    private Long revision = 0L;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (contentUpdatedAt == null) {
            contentUpdatedAt = createdAt;
        }
    }

    @Override
    public ContentEntityType contentType() {
        return ContentEntityType.COURSE;
    }

    @Override
    public String contentTitle() {
        return title;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Records that an instructor changed student-facing content.
     *
     * <p>The single place the content version moves. Callers pass the transaction's own instant
     * rather than reading the clock here, so every change made by one request carries one
     * timestamp and a publish in that same request lands exactly on it rather than a microsecond
     * behind it.
     *
     * <p>Never called for student progress, enrolment, purchases, analytics, or the background
     * video lookup — none of those is a change to the course.
     */
    @Override
    public void markContentChanged(LocalDateTime at) {
        this.contentUpdatedAt = at;
    }

    /**
     * Publishes the course and makes now the version baseline.
     *
     * <p>{@code contentUpdatedAt} is pulled back to the baseline when it is not already behind it,
     * which is what makes a publish that carries edits — the ordinary case, since the editor saves
     * before it publishes — come out at "no updates since publish" rather than instantly
     * announcing itself as updated.
     */
    public void markPublished(LocalDateTime at) {
        this.status = CourseStatus.PUBLISHED;
        this.lastPublishedAt = at;
        if (contentUpdatedAt == null || contentUpdatedAt.isAfter(at)) {
            this.contentUpdatedAt = at;
        }
    }

    /** Withdraws the course from the catalogue. The publication baseline is deliberately kept. */
    public void markUnpublished() {
        this.status = CourseStatus.DRAFT;
    }

    /**
     * Advances the aggregate revision. Called once per accepted request that changed something.
     *
     * <p>Tolerates a null on the way in so a row written by an instance that predates the column
     * still moves forward rather than failing; the column default means that cannot happen for a
     * row this build inserted.
     */
    public void nextRevision() {
        this.revision = revision == null ? 1L : revision + 1;
    }

    /**
     * Whether learners should be told this course has changed since they could last have seen it.
     *
     * <p>Derived, never stored, so it can never disagree with the timestamps it is derived from.
     * False for a draft (nobody is looking at it), false for a course that was never published
     * (there is no baseline to be newer than), and false for legacy rows the V5 back-fill gave an
     * equal pair of timestamps.
     */
    public boolean hasUpdatesSincePublish() {
        return status == CourseStatus.PUBLISHED
                && lastPublishedAt != null
                && contentUpdatedAt != null
                && contentUpdatedAt.isAfter(lastPublishedAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Course other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
