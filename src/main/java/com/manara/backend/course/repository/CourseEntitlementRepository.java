package com.manara.backend.course.repository;

import com.manara.backend.course.model.CourseEntitlement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CourseEntitlementRepository extends JpaRepository<CourseEntitlement, Long> {

    Optional<CourseEntitlement> findByCourseIdAndStudentId(Long courseId, Long studentId);

    /**
     * The read every checkout starts from.
     *
     * <p>Locking the row is what stops two concurrent renewals of the same subscription from both
     * seeing it expired and both charging: the second waits, and by the time it reads, the window
     * has already moved. It does not stop two concurrent <em>first</em> checkouts — there is no row
     * to lock yet — which is what the table's unique constraint is for.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CourseEntitlement e where e.course.id = :courseId and e.student.id = :studentId")
    Optional<CourseEntitlement> findForUpdate(@Param("courseId") Long courseId, @Param("studentId") Long studentId);
}
