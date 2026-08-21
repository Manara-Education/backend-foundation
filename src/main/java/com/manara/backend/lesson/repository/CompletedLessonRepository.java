package com.manara.backend.lesson.repository;

import com.manara.backend.lesson.model.CompletedLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CompletedLessonRepository extends JpaRepository<CompletedLesson, Long> {
    Optional<CompletedLesson> findByStudentIdAndLessonId(Long studentId, Long lessonId);

    int countByStudentIdAndLesson_Course_Id(Long studentId, Long courseId);

    @Query("select cl.lesson.id from CompletedLesson cl where cl.student.id = :studentId and cl.lesson.course.id = :courseId")
    List<Long> findCompletedLessonIdsByStudentIdAndCourseId(@Param("studentId") Long studentId, @Param("courseId") Long courseId);

    @Modifying
    @Query("delete from CompletedLesson cl where cl.lesson.id = :lessonId")
    void deleteByLessonId(@Param("lessonId") Long lessonId);

    /**
     * Used when a course update removes several lessons at once — deleting the lessons without
     * clearing their progress rows would violate the foreign key.
     *
     * <p>Flushes first so edits made earlier in the same synchronization pass are already written,
     * but deliberately does not clear the persistence context: a course update still holds the
     * course, its modules and its surviving lessons as managed entities, and detaching them
     * mid-pass would strand every change made after this point.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from CompletedLesson cl where cl.lesson.id in :lessonIds")
    void deleteByLessonIdIn(@Param("lessonIds") List<Long> lessonIds);
}
