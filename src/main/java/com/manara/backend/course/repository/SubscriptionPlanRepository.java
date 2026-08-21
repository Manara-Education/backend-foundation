package com.manara.backend.course.repository;

import com.manara.backend.course.model.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    List<SubscriptionPlan> findByCourseIdOrderByOrderIndexAsc(Long courseId);
}
