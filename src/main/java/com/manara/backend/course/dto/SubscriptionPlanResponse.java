package com.manara.backend.course.dto;

import com.manara.backend.course.model.SubscriptionUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubscriptionPlanResponse {

    private Long id;
    private String name;
    private Integer duration;
    private SubscriptionUnit unit;
    private BigDecimal price;
    private Integer orderIndex;
}
