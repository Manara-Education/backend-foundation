package com.manara.backend.quiz.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.hibernate.annotations.ColumnDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One ordered question of a {@link Quiz}.
 *
 * <p>The correct answer is not stored here. It is a flag on the owning {@link QuizOption}, which
 * keeps the insert graph acyclic: a {@code correct_option_id} column on this table would point at a
 * row that itself points back here, and a brand-new quiz would need a second write pass to resolve
 * it. The API contract still speaks {@code correctOptionId} — the mapper derives it from the option
 * carrying the flag.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "quiz_questions",
        indexes = @Index(name = "idx_quiz_questions_quiz_id", columnList = "quiz_id")
)
public class QuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Builder.Default
    @ColumnDefault("false")
    @Column(name = "hint_by_ai_enabled", nullable = false)
    private Boolean hintByAiEnabled = false;

    @Column(name = "order_index", nullable = false)
    private Integer orderIndex;

    @Builder.Default
    @OneToMany(mappedBy = "question", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<QuizOption> options = new ArrayList<>();

    public void addOption(QuizOption option) {
        option.setQuestion(this);
        options.add(option);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof QuizQuestion other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
