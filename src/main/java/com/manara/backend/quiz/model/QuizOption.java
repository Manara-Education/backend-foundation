package com.manara.backend.quiz.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;

import java.util.Objects;

/**
 * One ordered answer option of a {@link QuizQuestion}.
 *
 * <p>{@code correct} carries the answer key. Exactly one option per question is correct; the
 * invariant is enforced by the quiz validator before anything is written.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "quiz_options",
        indexes = @Index(name = "idx_quiz_options_question_id", columnList = "question_id")
)
public class QuizOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private QuizQuestion question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Builder.Default
    @ColumnDefault("false")
    @Column(name = "is_correct", nullable = false)
    private Boolean correct = false;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuizOption other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
