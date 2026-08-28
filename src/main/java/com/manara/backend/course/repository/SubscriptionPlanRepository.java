package com.manara.backend.course.repository;

import com.manara.backend.course.model.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    /**
     * The plans this course currently offers, in the order the instructor arranged them.
     *
     * <p>Retired plans are excluded everywhere an offer is read — the editor, the course screen and
     * checkout — because they are no longer on sale. They are still reachable by id, which is what
     * every historical entitlement and subscription needs and nothing else uses.
     */
    List<SubscriptionPlan> findByCourseIdAndRetiredAtIsNullOrderByOrderIndexAsc(Long courseId);

    /** Every plan the course has ever offered, retired ones included. For history, not for offers. */
    List<SubscriptionPlan> findByCourseIdOrderByOrderIndexAsc(Long courseId);
}
