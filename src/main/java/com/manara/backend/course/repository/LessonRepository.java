package com.manara.backend.course.repository;

import com.manara.backend.course.model.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> {
    List<Lesson> findByCourseIdOrderByOrderIndexAsc(Long courseId);
    int countByCourseId(Long courseId);
    
    @Query("SELECT COALESCE(SUM(l.duration), 0) FROM Lesson l WHERE l.course.id = :courseId")
    Integer sumDurationByCourseId(@Param("courseId") Long courseId);
}
