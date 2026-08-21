package com.manara.backend.lesson.repository;

import com.manara.backend.lesson.model.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> {

    List<Lesson> findByCourseIdOrderByOrderIndexAsc(Long courseId);

    /**
     * Course-wide reading order for both structures: modules first in their own order, then lessons
     * in theirs. Flat courses have no module, so {@code coalesce} keeps them on a single level and
     * the result is identical to ordering by {@code orderIndex} alone.
     *
     * <p>The trailing {@code l.id} keeps the order total, so two lessons sharing an order index
     * never swap places between requests.
     */
    @Query("""
            select l from Lesson l
            left join fetch l.module m
            where l.course.id = :courseId
            order by coalesce(m.orderIndex, 0) asc, l.orderIndex asc, l.id asc
            """)
    List<Lesson> findCourseLessonsInReadingOrder(@Param("courseId") Long courseId);

    int countByCourseId(Long courseId);

    /** Lessons currently active in a {@code FLAT} course. */
    int countByCourseIdAndModuleIsNull(Long courseId);

    /** Lessons currently active in a {@code MODULES} course. */
    int countByCourseIdAndModuleIsNotNull(Long courseId);

    @Query("SELECT COALESCE(SUM(l.duration), 0) FROM Lesson l WHERE l.course.id = :courseId")
    Integer sumDurationByCourseId(@Param("courseId") Long courseId);
}
