package com.manara.backend.course.service;

import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionUnit;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Turns a plan's duration into an actual end date.
 *
 * <p>The only place an expiry is produced. A client never sends one, and never could: the plan's
 * {@code duration} and {@code unit} are read from the stored plan after it has been confirmed to
 * belong to the course being bought.
 *
 * <p>Renewing before the current window closes extends from the existing end rather than from now,
 * so a learner who renews early is not silently charged for days they already own.
 */
@Component
public class SubscriptionWindow {

    /**
     * @param currentExpiry the end of the window being renewed, or {@code null} when there is none
     *                      to extend — a first subscription, or one that already lapsed
     */
    public LocalDateTime startOf(LocalDateTime now, LocalDateTime currentExpiry) {
        return currentExpiry != null && currentExpiry.isAfter(now) ? currentExpiry : now;
    }

    public LocalDateTime endOf(LocalDateTime start, SubscriptionPlan plan) {
        return advance(start, plan.getUnit(), plan.getDuration());
    }

    private LocalDateTime advance(LocalDateTime from, SubscriptionUnit unit, int duration) {
        return switch (unit) {
            case DAY -> from.plusDays(duration);
            case WEEK -> from.plusWeeks(duration);
            case MONTH -> from.plusMonths(duration);
        };
    }
}
