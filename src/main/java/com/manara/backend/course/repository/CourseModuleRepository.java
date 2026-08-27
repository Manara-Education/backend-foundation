package com.manara.backend.course.repository;

import com.manara.backend.course.model.CourseModule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CourseModuleRepository extends JpaRepository<CourseModule, Long> {

    /**
     * A course's modules in curriculum order.
     *
     * <p>{@code order_index} is unique per course from V5 onwards, so the {@code id} tiebreaker is
     * belt to that braces: it keeps the order total for a row written by an application version
     * that predates the constraint, so two modules can never quietly swap places between two reads
     * of the same course.
     */
    @Query("select m from CourseModule m where m.course.id = :courseId order by m.orderIndex asc, m.id asc")
    List<CourseModule> findByCourseIdOrderByOrderIndexAsc(@Param("courseId") Long courseId);

    /**
     * The same list, with the rows locked for the rest of the transaction.
     *
     * <p>Used by the reorder command alone. Two reorders of one course arriving together would
     * otherwise both read the same positions and both write a permutation of them, and the result
     * of interleaving two permutations is neither of the orders the instructor asked for. Taking
     * the row locks up front makes the second reorder wait, re-read, and then either apply cleanly
     * or be rejected because the module set it named no longer matches.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from CourseModule m where m.course.id = :courseId order by m.orderIndex asc, m.id asc")
    List<CourseModule> findByCourseIdForUpdate(@Param("courseId") Long courseId);

    /**
     * One module, resolved against the course that claims to own it.
     *
     * <p>Both halves of the path are matched, so a module id from another instructor's course
     * cannot be reached by pairing it with a course of one's own. The nested-lesson order command
     * uses this as its ownership gate: the course is checked first, the module is checked against
     * that course here, and only then is a lesson scope read.
     */
    Optional<CourseModule> findByIdAndCourseId(Long id, Long courseId);
}
