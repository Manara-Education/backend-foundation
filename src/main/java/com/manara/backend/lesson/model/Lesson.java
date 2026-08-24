package com.manara.backend.lesson.model;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.video.model.VideoSource;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "lessons",
        indexes = {
                @Index(name = "idx_lessons_course_id", columnList = "course_id"),
                @Index(name = "idx_lessons_module_id", columnList = "module_id")
        }
)
public class Lesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * The lesson's video, whoever hosts it.
     *
     * <p>Embedded, so {@code video.url} is still the {@code lessons.video_url} column the prototype
     * wrote and no existing row had to move. What is new beside it is the provider, the provider's
     * own id, and a thumbnail — see {@link VideoSource} for which of those are authoritative.
     */
    @Embedded
    private VideoSource video;

    @Column
    private Integer duration;

    @Column(nullable = false)
    private Integer orderIndex;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /**
     * Set only while the owning course uses {@link com.manara.backend.course.model.CourseStructure#MODULES}.
     * The course reference above stays populated either way, which is what keeps every existing
     * course-scoped query (lesson count, duration, progress) working for both structures.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id")
    private CourseModule module;

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
        if (!(o instanceof Lesson other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
