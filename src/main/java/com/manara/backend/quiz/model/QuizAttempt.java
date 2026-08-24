package com.manara.backend.quiz.model;

import com.manara.backend.course.model.Course;
import com.manara.backend.profile.model.Student;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One graded submission of a {@link Quiz} by one student.
 *
 * <p>The row is the authoritative record of the result: {@code score}, {@code passingScore} and
 * {@code passed} are stored rather than recomputed, so an attempt still reads correctly after the
 * instructor edits the quiz or moves its pass mark. Nothing a client sends contributes to them.
 *
 * <p>{@code course} is carried alongside the quiz on purpose. Quiz ownership is polymorphic, so
 * there is no join that reaches a course from a quiz; storing it here is what lets one query load
 * every attempt a learner has made inside a course, which is what progression is computed from.
 *
 * <p>The foreign key to {@code quizzes} cascades on delete: removing a lesson, module or course
 * removes its quiz, and an attempt at a quiz that no longer exists has nothing left to describe.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "quiz_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_quiz_attempts_student_quiz_number",
                columnNames = {"student_id", "quiz_id", "attempt_number"}),
        indexes = {
                // Progression reads every attempt a student made in one course; the pass/best-score
                // lookups for a single quiz are served by the unique constraint's index above.
                @Index(name = "idx_quiz_attempts_student_course", columnList = "student_id, course_id")
        }
)
public class QuizAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Quiz quiz;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** 1 for the first submission, incremented per further attempt at the same quiz. */
    @Column(name = "attempt_number", nullable = false)
    private Integer attemptNumber;

    @Column(name = "correct_count", nullable = false)
    private Integer correctCount;

    @Column(name = "total_questions", nullable = false)
    private Integer totalQuestions;

    /** Percentage of questions answered correctly, 0-100. */
    @Column(nullable = false)
    private Integer score;

    /** The pass mark that applied at submission time, copied so history stays readable. */
    @Column(name = "passing_score", nullable = false)
    private Integer passingScore;

    @Column(nullable = false)
    private Boolean passed;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    @Builder.Default
    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<QuizAttemptAnswer> answers = new ArrayList<>();

    public void addAnswer(QuizAttemptAnswer answer) {
        answer.setAttempt(this);
        answers.add(answer);
    }

    @PrePersist
    protected void onCreate() {
        if (submittedAt == null) {
            submittedAt = LocalDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuizAttempt other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
