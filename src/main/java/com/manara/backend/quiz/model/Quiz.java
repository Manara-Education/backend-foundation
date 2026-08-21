package com.manara.backend.quiz.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The single quiz aggregate used everywhere in the product.
 *
 * <p>Ownership is polymorphic ({@code owner_type} + {@code owner_id}) so that lesson quizzes,
 * module exams and course final exams share one table, one validator, one mapper and one
 * synchronization algorithm. The unique constraint on the owner pair enforces the domain rule that
 * an owner has at most one active quiz.
 *
 * <p>Because the owner reference is polymorphic it cannot carry a database foreign key. The
 * lifecycle is therefore owned by the application: every path that removes a course, module or
 * lesson deletes the quiz it owned inside the same transaction. Questions and options, by contrast,
 * live entirely inside this aggregate and are managed by JPA cascade + orphan removal.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "quizzes",
        // Also serves as the lookup index for findByOwner — Postgres backs a unique constraint
        // with a btree index, so a separate @Index here would be redundant.
        uniqueConstraints = @UniqueConstraint(name = "uk_quizzes_owner", columnNames = {"owner_type", "owner_id"})
)
public class Quiz {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private QuizOwnerType ownerType;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String instructions;

    /** Percentage of the quiz a learner must answer correctly to pass, 1-100. */
    @Column(name = "passing_score", nullable = false)
    private Integer passingScore;

    @Builder.Default
    @OneToMany(mappedBy = "quiz", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<QuizQuestion> questions = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public void addQuestion(QuizQuestion question) {
        question.setQuiz(this);
        questions.add(question);
    }

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
        if (!(o instanceof Quiz other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
