package com.manara.backend.course.repository;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {

    List<Course> findByInstructorId(Long instructorId);

    /**
     * List queries render the instructor's name, so the instructor and its user are fetched up
     * front. Without this every row triggered two extra selects.
     */
    @Query("select c from Course c join fetch c.instructor i join fetch i.user")
    List<Course> findAllWithInstructor();

    @Query("select c from Course c join fetch c.instructor i join fetch i.user where c.status = :status")
    List<Course> findAllByStatusWithInstructor(@Param("status") CourseStatus status);

    @Query("select c from Course c join fetch c.instructor i join fetch i.user where c.instructor.id = :instructorId")
    List<Course> findByInstructorIdWithInstructor(@Param("instructorId") Long instructorId);
}
