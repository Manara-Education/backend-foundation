package com.manara.backend.quiz.repository;

import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOwnerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface QuizRepository extends JpaRepository<Quiz, Long> {

    Optional<Quiz> findByOwnerTypeAndOwnerId(QuizOwnerType ownerType, Long ownerId);

    List<Quiz> findByOwnerTypeAndOwnerIdIn(QuizOwnerType ownerType, Collection<Long> ownerIds);

    /**
     * Batch read for a whole course aggregate: all quizzes of one owner type with their questions
     * already initialized, so rendering a course editor costs a fixed number of queries instead of
     * one per lesson.
     *
     * <p>Deliberately no bulk {@code delete} counterpart — a JPQL bulk delete bypasses cascade and
     * orphan removal and would leave questions and options behind. Deletion always goes through
     * entity removal.
     */
    @Query("""
            select distinct q from Quiz q
            left join fetch q.questions
            where q.ownerType = :ownerType and q.ownerId in :ownerIds
            """)
    List<Quiz> findByOwnerTypeAndOwnerIdInWithQuestions(@Param("ownerType") QuizOwnerType ownerType,
                                                        @Param("ownerIds") Collection<Long> ownerIds);
}
