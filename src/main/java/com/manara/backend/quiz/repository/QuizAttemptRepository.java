package com.manara.backend.quiz.repository;

import com.manara.backend.quiz.model.QuizAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface QuizAttemptRepository extends JpaRepository<QuizAttempt, Long> {

    /**
     * Every attempt a student has made anywhere in one course, oldest first.
     *
     * <p>Progression needs the pass state of the lesson quizzes, module exams and final exam all at
     * once, so it reads them in a single query rather than one per quiz. Answers stay lazy: the
     * rows this drives are decided by {@code score} and {@code passed} on the attempt itself.
     */
    @Query("""
            select a from QuizAttempt a
            where a.student.id = :studentId and a.course.id = :courseId
            order by a.id asc
            """)
    List<QuizAttempt> findCourseAttempts(@Param("studentId") Long studentId, @Param("courseId") Long courseId);

    int countByStudentIdAndQuizId(Long studentId, Long quizId);
}
