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
 * What a student chose for one question of a {@link QuizAttempt}, as it stood when they submitted.
 *
 * <h2>A submission is evidence, not a view of the quiz</h2>
 * The quiz goes on being edited after a learner sits it. The record of what they sat must not.
 *
 * <p>Both content foreign keys used to cascade on delete, so an instructor removing the option a
 * learner had chosen deleted that learner's answer row — while {@link QuizAttempt}, a separate
 * table, went on reading {@code score=100, correct_count=1}. The two halves of one record
 * disagreed: a result screen with a score and nothing behind it. Rewriting the score instead would
 * have been worse; it is a real thing that really happened.
 *
 * <p>So the authoring references are nullable now and clear themselves when the question or option
 * they name is deleted ({@code ON DELETE SET NULL}), and everything the row actually needs in order
 * to be read back is copied onto it at submission time: {@link #questionText},
 * {@link #selectedOptionText}, {@link #correctOptionText} and {@link #correct}. The ids stay
 * useful while the authoring rows exist and stop being load-bearing the moment they do not.
 *
 * <p>{@code correct} was already stored rather than derived, for the same reason one step smaller:
 * an instructor moving the answer key must not silently rewrite a past result.
 *
 * <p>The one deletion that still takes attempts with it is deleting the quiz itself — the
 * {@code attempt_id} and {@code quiz_id} cascades are unchanged. Removing a quiz, a lesson or a
 * module is the documented destructive authoring operation; an attempt at a quiz that no longer
 * exists has nothing left to describe, and it is deleted whole rather than left as a score with no
 * questions.
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

    /**
     * The question as it was authored when this answer was given, or {@code null} once it has been
     * deleted. Read {@link #questionText} for what the learner was actually asked.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private QuizQuestion question;

    /** The chosen option, or {@code null} once it has been deleted. See {@link #selectedOptionText}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "selected_option_id")
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private QuizOption selectedOption;

    /** What the learner was asked, in the words it was asked in. */
    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;

    /** What the learner chose, in the words they chose it by. */
    @Column(name = "selected_option_text", columnDefinition = "TEXT")
    private String selectedOptionText;

    /** What the answer key said at the time — not what it says now. */
    @Column(name = "correct_option_text", columnDefinition = "TEXT")
    private String correctOptionText;

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
