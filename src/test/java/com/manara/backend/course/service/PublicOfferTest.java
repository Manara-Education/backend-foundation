package com.manara.backend.course.service;

import com.manara.backend.course.dto.PublicPricingStatus;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule every public price comes from. What matters most is the negative half: a missing or zero
 * amount must never come out as free, and a plan checkout would not sell must never be offered.
 */
class PublicOfferTest {

    private static Course course(CourseAccessType accessType, String purchasePrice) {
        return Course.builder()
                .id(7L)
                .title("Course")
                .accessType(accessType)
                .purchasePrice(purchasePrice == null ? null : new BigDecimal(purchasePrice))
                .build();
    }

    private static SubscriptionPlan plan(long id, String name, int orderIndex, Integer duration,
                                         SubscriptionUnit unit, String price) {
        return SubscriptionPlan.builder()
                .id(id)
                .name(name)
                .orderIndex(orderIndex)
                .duration(duration)
                .unit(unit)
                .price(price == null ? null : new BigDecimal(price))
                .build();
    }

    @Nested
    @DisplayName("FREE")
    class Free {

        @Test
        @DisplayName("is decided by the access type, with no price and no plans")
        void freeIsTheAccessType() {
            var offer = PublicOffer.of(course(CourseAccessType.FREE, null), List.of());

            assertThat(offer.accessType()).isEqualTo(CourseAccessType.FREE);
            assertThat(offer.pricingStatus()).isEqualTo(PublicPricingStatus.FREE);
            assertThat(offer.purchasePrice()).isNull();
            assertThat(offer.plans()).isEmpty();
        }

        @Test
        @DisplayName("ignores a leftover price and leftover plans")
        void freeIgnoresLeftovers() {
            var offer = PublicOffer.of(course(CourseAccessType.FREE, "99.00"),
                    List.of(plan(1, "Monthly", 0, 1, SubscriptionUnit.MONTH, "100.00")));

            assertThat(offer.pricingStatus()).isEqualTo(PublicPricingStatus.FREE);
            assertThat(offer.purchasePrice()).isNull();
            assertThat(offer.plans()).isEmpty();
        }
    }

    @Nested
    @DisplayName("PURCHASE")
    class Purchase {

        @Test
        @DisplayName("a price above zero is PRICED, stated at two decimal places")
        void positivePriceIsPriced() {
            var offer = PublicOffer.of(course(CourseAccessType.PURCHASE, "450"), List.of());

            assertThat(offer.pricingStatus()).isEqualTo(PublicPricingStatus.PRICED);
            assertThat(offer.purchasePrice()).isEqualTo(new BigDecimal("450.00"));
            assertThat(offer.purchasePrice().scale()).isEqualTo(2);
        }

        @ParameterizedTest(name = "price {0} is UNAVAILABLE, not free")
        @NullSource
        @ValueSource(strings = {"0", "0.00", "-5.00", "10.005"})
        void unusablePriceIsUnavailable(String price) {
            var offer = PublicOffer.of(course(CourseAccessType.PURCHASE, price), List.of());

            assertThat(offer.accessType()).isEqualTo(CourseAccessType.PURCHASE);
            assertThat(offer.pricingStatus()).isEqualTo(PublicPricingStatus.UNAVAILABLE);
            assertThat(offer.purchasePrice()).isNull();
        }

        @Test
        @DisplayName("ignores plans a purchase course still has")
        void purchaseIgnoresPlans() {
            var offer = PublicOffer.of(course(CourseAccessType.PURCHASE, "300.00"),
                    List.of(plan(1, "Monthly", 0, 1, SubscriptionUnit.MONTH, "100.00")));

            assertThat(offer.plans()).isEmpty();
        }
    }

    @Nested
    @DisplayName("SUBSCRIPTION")
    class Subscription {

        @Test
        @DisplayName("offers only the plans checkout would sell, in the instructor's order")
        void onlySellablePlansInOrder() {
            var retired = plan(1, "Retired", 0, 1, SubscriptionUnit.MONTH, "50.00");
            retired.retire(LocalDateTime.now());
            var zeroPrice = plan(2, "Zero", 1, 1, SubscriptionUnit.MONTH, "0.00");
            var zeroTerm = plan(3, "No term", 2, 0, SubscriptionUnit.MONTH, "80.00");
            var noUnit = plan(4, "No unit", 3, 1, null, "80.00");
            var yearly = plan(5, "Yearly", 5, 12, SubscriptionUnit.MONTH, "900");
            var weekly = plan(6, "Weekly", 4, 1, SubscriptionUnit.WEEK, "40.00");

            var offer = PublicOffer.of(course(CourseAccessType.SUBSCRIPTION, null),
                    List.of(retired, zeroPrice, zeroTerm, noUnit, yearly, weekly));

            assertThat(offer.pricingStatus()).isEqualTo(PublicPricingStatus.PRICED);
            assertThat(offer.plans()).extracting(SubscriptionPlan::getName).containsExactly("Weekly", "Yearly");
            assertThat(offer.purchasePrice()).isNull();
        }

        @Test
        @DisplayName("with no sellable plan is UNAVAILABLE, with none listed")
        void noSellablePlanIsUnavailable() {
            var offer = PublicOffer.of(course(CourseAccessType.SUBSCRIPTION, null),
                    List.of(plan(1, "Zero", 0, 1, SubscriptionUnit.MONTH, "0")));

            assertThat(offer.pricingStatus()).isEqualTo(PublicPricingStatus.UNAVAILABLE);
            assertThat(offer.plans()).isEmpty();
        }

        @Test
        @DisplayName("with no plans at all is UNAVAILABLE")
        void noPlansIsUnavailable() {
            assertThat(PublicOffer.of(course(CourseAccessType.SUBSCRIPTION, null), List.of()).pricingStatus())
                    .isEqualTo(PublicPricingStatus.UNAVAILABLE);
            assertThat(PublicOffer.of(course(CourseAccessType.SUBSCRIPTION, null), null).pricingStatus())
                    .isEqualTo(PublicPricingStatus.UNAVAILABLE);
        }

        @Test
        @DisplayName("a stale one-off price never becomes the subscription's price")
        void stalePurchasePriceIsIgnored() {
            var offer = PublicOffer.of(course(CourseAccessType.SUBSCRIPTION, "300.00"),
                    List.of(plan(1, "Monthly", 0, 1, SubscriptionUnit.MONTH, "100.00")));

            assertThat(offer.purchasePrice()).isNull();
        }
    }

    @Test
    @DisplayName("money is stated exactly, or not at all")
    void moneyIsExactOrAbsent() {
        assertThat(PublicOffer.money(new BigDecimal("100.5"))).isEqualTo(new BigDecimal("100.50"));
        assertThat(PublicOffer.money(new BigDecimal("100.500"))).isEqualTo(new BigDecimal("100.50"));
        assertThat(PublicOffer.money(new BigDecimal("0.01"))).isEqualTo(new BigDecimal("0.01"));
        assertThat(PublicOffer.money(new BigDecimal("0.001"))).isNull();
        assertThat(PublicOffer.money(BigDecimal.ZERO)).isNull();
        assertThat(PublicOffer.money(null)).isNull();
    }
}
