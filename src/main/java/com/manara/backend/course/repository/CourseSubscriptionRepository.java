package com.manara.backend.course.repository;

import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.course.model.SubscriptionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CourseSubscriptionRepository extends JpaRepository<CourseSubscription, Long> {

    /** Whether any subscription term was ever bought against this plan. */
    boolean existsByPlanId(Long planId);

    List<CourseSubscription> findByCourseIdAndStudentIdAndStatus(
            Long courseId, Long studentId, SubscriptionStatus status);

    /** The most recent term, whether or not it is still open — what the renewal screen describes. */
    Optional<CourseSubscription> findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(
            Long courseId, Long studentId);

    /** A learner's subscriptions, newest first, with what the list shows fetched in the same query. */
    @Query(value = """
            select s from CourseSubscription s join fetch s.course c join fetch c.instructor i join fetch i.user
                join fetch s.plan
            where s.student.id = :studentId
            order by s.startsAt desc, s.id desc
            """,
            countQuery = "select count(s) from CourseSubscription s where s.student.id = :studentId")
    Page<CourseSubscription> findPageForStudent(@Param("studentId") Long studentId, Pageable pageable);

    @Query("""
            select count(s) from CourseSubscription s
            where s.student.id = :studentId
              and s.status = com.manara.backend.course.model.SubscriptionStatus.ACTIVE
              and s.expiresAt > :now
            """)
    long countActive(@Param("studentId") Long studentId, @Param("now") LocalDateTime now);
}
