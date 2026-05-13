package com.manara.backend.course.repository;

import com.manara.backend.course.model.CompletedLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CompletedLessonRepository extends JpaRepository<CompletedLesson, Long> {
    Optional<CompletedLesson> findByStudentIdAndLessonId(Long studentId, Long lessonId);

    int countByStudentIdAndLesson_Course_Id(Long studentId, Long courseId);
}
