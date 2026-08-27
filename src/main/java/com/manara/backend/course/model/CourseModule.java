package com.manara.backend.course.model;

import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * A chapter of a {@link CourseStructure#MODULES} course. Named {@code CourseModule} rather than
 * {@code Module} to stay clear of {@link java.lang.Module}.
 *
 * <p>Modules only carry structure — the lessons underneath them keep pointing at the course too, so
 * every course-scoped query (lesson count, duration, progress) keeps working unchanged for both
 * structures.
 */
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
@Table(
        name = "course_modules",
        indexes = @Index(name = "idx_course_modules_course_id", columnList = "course_id")
)
public class CourseModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

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
        if (!(o instanceof CourseModule other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
