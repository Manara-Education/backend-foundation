package com.manara.backend.course.service;

import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionUnit;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a subscription's end date comes from.
 *
 * <p>Every case here is arithmetic the server does on its own. Nothing a client can send appears in
 * any of it — that is the point of the class existing separately from the checkout.
 */
class SubscriptionWindowTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 10, 0);

    private final SubscriptionWindow subscriptionWindow = new SubscriptionWindow();

    @Test
    void aDayPlanEndsThatManyDaysOn() {
        assertThat(subscriptionWindow.endOf(NOW, plan(SubscriptionUnit.DAY, 10)))
                .isEqualTo(LocalDateTime.of(2026, 8, 31, 10, 0));
    }

    @Test
    void aWeekPlanEndsThatManyWeeksOn() {
        assertThat(subscriptionWindow.endOf(NOW, plan(SubscriptionUnit.WEEK, 3)))
                .isEqualTo(LocalDateTime.of(2026, 9, 11, 10, 0));
    }

    @Test
    void aMonthPlanEndsOnTheSameDayOfTheMonth() {
        assertThat(subscriptionWindow.endOf(NOW, plan(SubscriptionUnit.MONTH, 6)))
                .isEqualTo(LocalDateTime.of(2027, 2, 21, 10, 0));
    }

    /** Calendar months, not 30-day blocks: a month from 31 January is the end of February. */
    @Test
    void aMonthPlanClampsToTheShorterMonth() {
        assertThat(subscriptionWindow.endOf(LocalDateTime.of(2026, 1, 31, 9, 0), plan(SubscriptionUnit.MONTH, 1)))
                .isEqualTo(LocalDateTime.of(2026, 2, 28, 9, 0));
    }

    @Test
    void aFirstSubscriptionStartsNow() {
        assertThat(subscriptionWindow.startOf(NOW, null)).isEqualTo(NOW);
    }

    @Test
    void renewingEarlyExtendsFromTheCurrentEndRatherThanFromNow() {
        LocalDateTime stillOpen = NOW.plusDays(5);
        assertThat(subscriptionWindow.startOf(NOW, stillOpen)).isEqualTo(stillOpen);
    }

    /** A window that already closed buys nothing back: the new one starts today. */
    @Test
    void renewingAfterExpiryStartsNow() {
        assertThat(subscriptionWindow.startOf(NOW, NOW.minusDays(3))).isEqualTo(NOW);
    }

    private SubscriptionPlan plan(SubscriptionUnit unit, int duration) {
        return SubscriptionPlan.builder()
                .id(1L)
                .name(duration + " " + unit)
                .unit(unit)
                .duration(duration)
                .price(BigDecimal.valueOf(100))
                .orderIndex(0)
                .build();
    }
}
