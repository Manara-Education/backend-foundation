package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manara.backend.course.model.SubscriptionUnit;

import java.math.BigDecimal;

/**
 * One subscription plan a visitor may choose, as the public catalogue shows it.
 *
 * <p>Only plans that are on sale and sellable reach this shape: active (not retired), with a price
 * and a duration above zero. The {@code id} is the one checkout takes as {@code planId}, so the plan
 * a visitor reads here is the plan they are later charged for.
 *
 * @param id       the plan's id, as checkout accepts it
 * @param name     the instructor's name for the plan
 * @param duration how many {@code unit}s one term lasts; always above zero
 * @param unit     {@code DAY}, {@code WEEK} or {@code MONTH}
 * @param price    the price of one term in EGP (pounds, not piastres), two decimal places, above zero
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicSubscriptionPlanResponse(
        Long id,
        String name,
        Integer duration,
        SubscriptionUnit unit,
        BigDecimal price) {
}
