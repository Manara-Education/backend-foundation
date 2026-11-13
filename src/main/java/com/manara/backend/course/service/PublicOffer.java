package com.manara.backend.course.service;

import com.manara.backend.course.dto.PublicPricingStatus;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.SubscriptionPlan;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What an anonymous visitor may be told a course costs, decided once.
 *
 * <p>The public catalogue's list, its detail page and the TECH-1 offer audit all have to agree about
 * this, so it is stated here and the three read it rather than each deciding. The rule is the same
 * one checkout charges by:
 *
 * <ul>
 *   <li>{@code FREE} is decided by the access type and by nothing else. A course is never free
 *       because a price is missing or zero.
 *   <li>{@code PURCHASE} costs {@code courses.price}, which is what
 *       {@link CheckoutProcessor} charges. A price that is missing or not above zero cannot be
 *       stated, so the offer is {@link PublicPricingStatus#UNAVAILABLE}.
 *   <li>{@code SUBSCRIPTION} costs whichever active plan the learner picks, each at its own price
 *       and term. Only plans checkout would actually sell are offered: not retired, and with a
 *       price and a duration above zero. With none left, the offer is unavailable.
 * </ul>
 *
 * <p>Amounts are Egyptian pounds with at most two decimal places, which is what the
 * {@code numeric(38,2)} columns hold. An amount that would need more cannot be a real stored price,
 * so it is treated as unavailable rather than rounded into one.
 *
 * @param accessType    how the course is sold
 * @param pricingStatus whether a truthful price can be stated
 * @param purchasePrice the one-off price at two decimal places, for a priced purchase only
 * @param plans         the sellable plans of a priced subscription, in the instructor's order
 */
public record PublicOffer(
        CourseAccessType accessType,
        PublicPricingStatus pricingStatus,
        BigDecimal purchasePrice,
        List<SubscriptionPlan> plans) {

    /** The only currency the platform charges in; see {@link CheckoutProcessor}. */
    public static final String CURRENCY = "EGP";

    private static final int MONEY_SCALE = 2;

    private static final Comparator<SubscriptionPlan> INSTRUCTOR_ORDER = Comparator
            .comparing(SubscriptionPlan::getOrderIndex, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SubscriptionPlan::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    public PublicOffer {
        plans = List.copyOf(plans);
    }

    /**
     * The offer a course makes to the public.
     *
     * @param course      a course already known to be discoverable — eligibility is the caller's
     *                    query, not this rule
     * @param activePlans the course's plans. Retired plans are ignored if they are passed anyway.
     */
    public static PublicOffer of(Course course, List<SubscriptionPlan> activePlans) {
        CourseAccessType accessType = course.getAccessType();
        return switch (accessType) {
            case FREE -> new PublicOffer(accessType, PublicPricingStatus.FREE, null, List.of());
            case PURCHASE -> {
                BigDecimal price = money(course.getPurchasePrice());
                yield price == null
                        ? new PublicOffer(accessType, PublicPricingStatus.UNAVAILABLE, null, List.of())
                        : new PublicOffer(accessType, PublicPricingStatus.PRICED, price, List.of());
            }
            case SUBSCRIPTION -> {
                List<SubscriptionPlan> sellable = activePlans == null ? List.of() : activePlans.stream()
                        .filter(Objects::nonNull)
                        .filter(PublicOffer::isSellable)
                        .sorted(INSTRUCTOR_ORDER)
                        .toList();
                yield new PublicOffer(accessType,
                        sellable.isEmpty() ? PublicPricingStatus.UNAVAILABLE : PublicPricingStatus.PRICED,
                        null, sellable);
            }
        };
    }

    /**
     * Whether checkout would sell this plan: on offer, and with a real price and a real term.
     */
    public static boolean isSellable(SubscriptionPlan plan) {
        return plan.isActive()
                && plan.getUnit() != null
                && plan.getDuration() != null && plan.getDuration() > 0
                && money(plan.getPrice()) != null;
    }

    /**
     * A stored amount as the public contract states it — above zero, exactly two decimal places —
     * or {@code null} if it cannot be stated truthfully.
     */
    public static BigDecimal money(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return null;
        }
        BigDecimal exact = amount.stripTrailingZeros();
        if (exact.scale() > MONEY_SCALE) {
            return null;
        }
        return exact.setScale(MONEY_SCALE);
    }
}
