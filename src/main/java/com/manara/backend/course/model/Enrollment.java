package com.manara.backend.course.model;

import com.manara.backend.profile.model.Student;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * That a learner joined a course, and how far through it they are.
 *
 * <p>Membership only. Whether they may currently <em>open</em> the course is
 * {@link CourseEntitlement}'s answer, and whether they paid is
 * {@link CourseSubscription}'s — an enrolment outlives both. That separation is what lets an expired
 * subscription close the content without erasing the learner's history: this row, and the completed
 * lessons and quiz attempts that hang off the same student, are never removed on expiry.
 *
 * <p>The {@code (course_id, student_id)} unique constraint is the real guarantee against duplicate
 * enrolments. The service checks first for a clean error message, but two concurrent checkouts both
 * pass that check; only the database can settle it.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "enrollments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_enrollments_course_student",
                columnNames = {"course_id", "student_id"}))
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Builder.Default
    @Column(nullable = false)
    private Integer progress = 0; // 0-100

    @Builder.Default
    @Column(nullable = false)
    private Boolean enrolled = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime enrolledAt;

    @PrePersist
    protected void onCreate() {
        enrolledAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Enrollment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
