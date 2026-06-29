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
    int countByCourseId(Long courseId);

    @Query("SELECT COALESCE(SUM(l.duration), 0) FROM Lesson l WHERE l.course.id = :courseId")
    Integer sumDurationByCourseId(@Param("courseId") Long courseId);
}
