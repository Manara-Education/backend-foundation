package com.manara.backend.course.repository;

import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.course.model.SubscriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}
