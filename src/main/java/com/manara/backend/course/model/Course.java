package com.manara.backend.course.model;

import com.manara.backend.profile.model.Instructor;
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
@Entity
@Table(name = "courses")
public class Course {

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

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
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
