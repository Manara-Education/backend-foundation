package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.dto.SubscriptionPlanResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.SubscriptionPlan;
import org.springframework.stereotype.Component;

@Component
public class SubscriptionPlanMapper {

    public SubscriptionPlan toSubscriptionPlan(SubscriptionPlanRequest request, Course course, int orderIndex) {
        return SubscriptionPlan.builder()
                .course(course)
                .name(request.getName().trim())
                .duration(request.getDuration())
                .unit(request.getUnit())
                .price(request.getPrice())
                .orderIndex(orderIndex)
                .build();
    }

    public SubscriptionPlanResponse toSubscriptionPlanResponse(SubscriptionPlan plan) {
        return SubscriptionPlanResponse.builder()
                .id(plan.getId())
                .name(plan.getName())
                .duration(plan.getDuration())
                .unit(plan.getUnit())
                .price(plan.getPrice())
                .orderIndex(plan.getOrderIndex())
                .build();
    }
}
