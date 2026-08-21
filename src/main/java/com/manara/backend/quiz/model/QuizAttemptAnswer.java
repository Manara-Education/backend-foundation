package com.manara.backend.quiz.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.Objects;

/**
 * What a student chose for one question of a {@link QuizAttempt}.
 *
 * <p>{@code correct} is stored rather than derived on read: an instructor may change which option
 * is the right one afterwards, and that must not silently rewrite a past result.
 *
 * <p>Both foreign keys cascade on delete. An attempt keeps its score and pass state no matter what
 * — those live on the attempt row, which only references the quiz — but the per-answer detail of a
 * question the instructor has since removed is gone with the question, which is the only honest
 * thing it can be.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "quiz_attempt_answers",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_quiz_attempt_answers_attempt_question",
                columnNames = {"attempt_id", "question_id"}),
        indexes = @Index(name = "idx_quiz_attempt_answers_attempt_id", columnList = "attempt_id")
)
public class QuizAttemptAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attempt_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizAttempt attempt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizQuestion question;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "selected_option_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private QuizOption selectedOption;

    @Column(name = "is_correct", nullable = false)
    private Boolean correct;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuizAttemptAnswer other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
