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

    /**
     * Whether this learner should be told their course has changed.
     *
     * <p>The whole business rule, and the only copy of it:
     *
     * <pre>{@code course.contentUpdatedAt > enrollment.enrolledAt}</pre>
     *
     * <p>Per enrollment, not per course. Two learners of one course get different answers — the one
     * who joined this morning bought the version that already contained everything, and telling
     * them it had been updated would be describing somebody else's experience of it.
     *
     * <p>It lives on this entity because this is the one object holding both halves of the
     * comparison. My Courses reads it straight off the enrollment it already loaded, and
     * {@code CourseUpdateWindow} asks the same method rather than repeating the line — a second
     * copy is how two screens end up disagreeing about the same course.
     *
     * <p>Note which timestamp is <em>not</em> used: {@code enrolledAt} is {@code updatable = false}
     * and nothing may ever move it. Clearing this badge by touching the enrollment date would
     * rewrite when the learner joined in order to change what they are shown, and would take their
     * whole change history with it.
     *
     * <p>Strictly after: a change landing in the same microsecond as the enrollment does not count,
     * because a learner who joined at that instant joined the changed version.
     */
    public boolean hasCourseUpdates() {
        if (course == null || enrolledAt == null) {
            return false;
        }
        LocalDateTime contentUpdatedAt = course.getContentUpdatedAt();
        return contentUpdatedAt != null && contentUpdatedAt.isAfter(enrolledAt);
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
