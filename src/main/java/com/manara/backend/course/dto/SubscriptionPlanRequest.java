package com.manara.backend.course.dto;

import com.manara.backend.course.model.SubscriptionUnit;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SubscriptionPlanRequest {

    /** Set to update an existing plan of this course; omit to create a new one. */
    private Long id;

    private String name;

    private Integer duration;

    private SubscriptionUnit unit;

    private BigDecimal price;
}
