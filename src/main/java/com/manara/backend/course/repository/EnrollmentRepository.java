package com.manara.backend.course.repository;

import com.manara.backend.course.model.Enrollment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {
    List<Enrollment> findByStudentId(Long studentId);
    List<Enrollment> findByCourseId(Long courseId);
    Optional<Enrollment> findByCourseIdAndStudentId(Long courseId, Long studentId);
    boolean existsByCourseIdAndStudentId(Long courseId, Long studentId);

    /**
     * A learner's enrollments with the course and its instructor already attached.
     *
     * <p>Every field My Courses renders reaches through the {@code LAZY} course — title, cover,
     * instructor name, and now {@link Enrollment#hasCourseUpdates()}. Letting each card initialise
     * its own proxy is two extra round trips per course before the card is even drawn; this is one
     * query for all of them.
     *
     * <p>Which is also why the per-enrollment badge is answered from two loaded fields rather than
     * from the change log: a list of cards must not read a table per card.
     */
    @Query("SELECT e FROM Enrollment e "
            + "JOIN FETCH e.course c "
            + "JOIN FETCH c.instructor i "
            + "JOIN FETCH i.user "
            + "WHERE e.student.id = :studentId")
    List<Enrollment> findByStudentIdWithCourse(@Param("studentId") Long studentId);
}
