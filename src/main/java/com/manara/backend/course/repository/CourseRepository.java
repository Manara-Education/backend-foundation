package com.manara.backend.course.repository;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /**
     * Every course a learner who does not already hold one may be shown.
     *
     * <p>The discovery rule as a query, and the reason it is a query rather than a filter applied
     * to the result of one. Discovery is counted, paginated and grouped by category; a private
     * course removed after the fact still occupies a row of the page, a place in the total, and a
     * number in a category count. Filtering in the {@code WHERE} clause means the pagination that
     * exists today and any that is added later operates on eligible courses only, and the counts
     * come out of the same predicate rather than a second, drifting copy of it.
     *
     * <p>It mirrors {@link Course#isDiscoverable()} exactly, which is the one duplication the rule
     * has: JPQL cannot call a derived method. The pair is covered by a test that asserts they agree
     * over the whole matrix of statuses and visibilities, so neither can be changed alone.
     */
    @Query("""
            select c from Course c join fetch c.instructor i join fetch i.user
            where c.status = com.manara.backend.course.model.CourseStatus.PUBLISHED
              and c.visibility = com.manara.backend.course.model.CourseVisibility.PUBLIC
            """)
    List<Course> findAllDiscoverableWithInstructor();

    @Query("select c from Course c join fetch c.instructor i join fetch i.user where c.instructor.id = :instructorId")
    List<Course> findByInstructorIdWithInstructor(@Param("instructorId") Long instructorId);

    /**
     * The course row, locked for the rest of the transaction. Every authoring write starts here.
     *
     * <p>What it protects is the revision check. Reading the revision, comparing it to the one the
     * client says it edited and then writing are three steps, and without the lock two requests
     * quoting the same revision both read it, both find it current, and both commit — which is the
     * lost update the revision exists to stop, merely made narrower. Taking the lock first makes
     * the check-and-increment atomic without a retry loop.
     *
     * <p>Scoped to one row. It does not touch another instructor's course, another course of the
     * same instructor, or any lesson scope — the ordering commands take their own locks underneath
     * this one, always in that order, so the two cannot deadlock against each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Course c where c.id = :courseId")
    Optional<Course> findByIdForUpdate(@Param("courseId") Long courseId);
}
