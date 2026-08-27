package com.manara.backend.course.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One thing an instructor did to a course, in the words a learner would use.
 *
 * <h2>Why this exists when the timestamps already work</h2>
 * It is deliberately <em>not</em> what decides whether something is new or updated. That is
 * arithmetic on {@link TrackedContent#getCreatedAt()} and
 * {@link TrackedContent#getContentUpdatedAt()}, it costs no query, and it cannot disagree with
 * itself. Two mechanisms answering the same question would eventually answer it differently, so
 * this one answers a different question: <em>what</em> happened, in a sentence.
 *
 * <p>Two things the timestamps provably cannot express, and the only two reasons this table is
 * here:
 *
 * <ul>
 *   <li><strong>Removal.</strong> A deleted lesson has no row left to carry a timestamp. Without
 *       these rows a learner's course simply loses a lesson between visits with no explanation.
 *   <li><strong>Movement.</strong> "Moved from Module 1 to Module 2" needs the parent the lesson
 *       used to have, and that is gone the moment the write commits.
 * </ul>
 *
 * <h2>Append-only</h2>
 * Rows are written inside the authoring transaction that caused them and never updated or deleted
 * afterwards. They carry no foreign key to the entity they describe — a {@code REMOVED} row would
 * be deleted with it, which is the one case it exists for — so {@link #entityTitle} is snapshotted
 * rather than joined to.
 *
 * <h2>Cost</h2>
 * One row per entity actually changed per authoring request: bounded by how often instructors edit,
 * not by how many learners read. Nothing on the My Courses path reads this table; only Course
 * Details does, in one indexed query filtered by the reader's own enrollment instant.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "course_changes",
        indexes = @Index(name = "idx_course_changes_course_occurred",
                columnList = "course_id, occurred_at"))
public class CourseChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * A plain column rather than a {@code @ManyToOne}. Writing one of these must not drag a course
     * into the persistence context, and reading a page of them must not fan out into course loads.
     */
    @Column(name = "course_id", nullable = false, updatable = false)
    private Long courseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false, length = 20)
    private ContentEntityType entityType;

    /**
     * The changed entity, or {@code null} for a change to the course itself.
     *
     * <p>Not a foreign key on purpose: a {@code REMOVED} row outlives the row it describes, and
     * that is the whole point of it.
     */
    @Column(name = "entity_id", updatable = false)
    private Long entityId;

    /** What the thing was called when it changed. The only name a removed entity still has. */
    @Column(name = "entity_title", nullable = false, updatable = false, length = 255)
    private String entityTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, updatable = false, length = 20)
    private ContentChangeType changeType;

    /**
     * The one extra fact the sentence needs, or {@code null} when it needs none.
     *
     * <p>Currently used by {@link ContentChangeType#MOVED} alone, where it holds the title of the
     * module the lesson came from. Kept as one open column rather than a pair of typed ones because
     * every other change type reads perfectly well without it, and a schema that forces them all to
     * carry an empty pair is describing the exception rather than the rule.
     */
    @Column(name = "detail", updatable = false, length = 255)
    private String detail;

    /**
     * When it happened — the authoring transaction's own instant, the same one written to the
     * changed entity's {@code content_updated_at} and to the course's.
     *
     * <p>Shared rather than read again here, so a learner filtering "everything since I enrolled"
     * can never see a course marked updated whose changes all sort a microsecond before their
     * enrollment.
     */
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CourseChange other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
