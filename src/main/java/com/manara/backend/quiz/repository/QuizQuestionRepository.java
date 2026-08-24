package com.manara.backend.quiz.repository;

import com.manara.backend.quiz.model.QuizQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface QuizQuestionRepository extends JpaRepository<QuizQuestion, Long> {

    /**
     * Loads every question of the given quizzes in one query, options included, so rendering a
     * course editor never degrades into a question-per-quiz / option-per-question fan-out.
     */
    @Query("""
            select distinct q from QuizQuestion q
            left join fetch q.options
            where q.quiz.id in :quizIds
            """)
    List<QuizQuestion> findAllByQuizIdInWithOptions(@Param("quizIds") Collection<Long> quizIds);
}
