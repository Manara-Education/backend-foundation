package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.course.service.CourseCheckoutService;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens to a subscription plan somebody has already bought.
 *
 * <h2>The failure this closes</h2>
 * Plans the payload no longer mentioned were deleted outright. {@code course_entitlements} and
 * {@code course_subscriptions} both reference them, so the database refused — and one subscriber
 * was enough to make a plan permanently undeletable and to trap the course in {@code SUBSCRIPTION}
 * forever. What the instructor saw was {@code 409 "The request conflicts with data that already
 * exists"}, with no indication of why or what to do, on both operations, every time.
 *
 * <p>The rule underneath is that a plan is two things at once. It is an <em>offer</em>, which the
 * instructor owns and may withdraw; and, from the moment somebody buys it, part of a <em>contract</em>,
 * which they do not own and may not rewrite. Editing a plan already respected that — renaming or
 * re-pricing one never touched an existing subscriber's term. Removing one now does too.
 */
class SubscriptionPlanRetirementTest extends AbstractCourseAuthoringTest {

    @Autowired CourseCheckoutService courseCheckoutService;
    @Autowired SubscriptionPlanRepository subscriptionPlanRepository;
    @Autowired CourseEntitlementRepository courseEntitlementRepository;
    @Autowired CourseSubscriptionRepository courseSubscriptionRepository;

    private User subscriber;

    @BeforeEach
    void createSubscriber() {
        subscriber = newStudentUser();
    }

    /** A published subscription course offering a monthly and a yearly plan. */
    private InstructorCourseResponse subscriptionCourse() {
        var request = flatCourse("Subscribed", CourseStatus.PUBLISHED, lesson("L1"));
        request.setAccessType(CourseAccessType.SUBSCRIPTION);
        request.setSubscriptionPlans(List.of(
                plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00"),
                plan("Yearly", 12, SubscriptionUnit.MONTH, "900.00")));
        return courseService.createCourse(instructorUser, request);
    }

    /** Buys a plan the way a learner would — through checkout, so every row it writes is real. */
    private void subscribeTo(Long courseId, Long planId) {
        courseCheckoutService.checkout(subscriber, courseId, CheckoutRequest.builder()
                .planId(planId)
                .paymentMethod(PaymentMethodRequest.builder().name("A Learner").email("learner@manara.test").build())
                .build());
    }

    private Long planNamed(InstructorCourseResponse course, String name) {
        return course.getSubscriptionPlans().stream()
                .filter(plan -> plan.getName().equals(name))
                .findFirst().orElseThrow().getId();
    }

    private InstructorCourseResponse asLoadedNow(Long courseId) {
        return courseService.getCourseForEditing(instructorUser, courseId);
    }

    @Test
    @DisplayName("removing a plan a learner holds retires it, and their contract is untouched")
    void removingABoughtPlanRetiresItAndKeepsTheContract() {
        var course = subscriptionCourse();
        Long monthly = planNamed(course, "Monthly");
        subscribeTo(course.getId(), monthly);

        var entitlementBefore = courseEntitlementRepository
                .findByCourseIdAndStudentId(course.getId(), studentProfileOf(subscriber).getId())
                .orElseThrow();
        LocalDateTime boughtExpiry = entitlementBefore.getExpiresAt();
        var subscriptionBefore = courseSubscriptionRepository
                .findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(
                        course.getId(), studentProfileOf(subscriber).getId())
                .orElseThrow();
        BigDecimal pricePaid = subscriptionBefore.getPricePaid();

        // The instructor drops Monthly from the offer and keeps Yearly.
        var edit = echoOf(asLoadedNow(course.getId()));
        edit.setSubscriptionPlans(edit.getSubscriptionPlans().stream()
                .filter(plan -> !plan.getId().equals(monthly))
                .toList());
        var saved = courseService.updateCourse(instructorUser, course.getId(), edit);

        // The save succeeds — it used to be a permanent 409.
        assertThat(saved.getSubscriptionPlans()).extracting(p -> p.getName()).containsExactly("Yearly");

        // The row is still there, marked retired rather than deleted.
        var retired = subscriptionPlanRepository.findById(monthly).orElseThrow();
        assertThat(retired.isActive()).isFalse();
        assertThat(retired.getRetiredAt()).isNotNull();

        // And the learner's contract is exactly as they bought it.
        var entitlementAfter = courseEntitlementRepository
                .findByCourseIdAndStudentId(course.getId(), studentProfileOf(subscriber).getId())
                .orElseThrow();
        assertThat(entitlementAfter.getSubscriptionPlan().getId()).isEqualTo(monthly);
        assertThat(entitlementAfter.getExpiresAt()).isEqualTo(boughtExpiry);
        assertThat(entitlementAfter.isActiveAt(LocalDateTime.now())).isTrue();

        var subscriptionAfter = courseSubscriptionRepository
                .findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(
                        course.getId(), studentProfileOf(subscriber).getId())
                .orElseThrow();
        assertThat(subscriptionAfter.getPricePaid()).isEqualByComparingTo(pricePaid);
        assertThat(subscriptionAfter.getExpiresAt()).isEqualTo(subscriptionBefore.getExpiresAt());
    }

    @Test
    @DisplayName("a retired plan cannot be bought by anybody else")
    void aRetiredPlanCannotBeBought() {
        var course = subscriptionCourse();
        Long monthly = planNamed(course, "Monthly");
        subscribeTo(course.getId(), monthly);

        var edit = echoOf(asLoadedNow(course.getId()));
        edit.setSubscriptionPlans(edit.getSubscriptionPlans().stream()
                .filter(plan -> !plan.getId().equals(monthly))
                .toList());
        courseService.updateCourse(instructorUser, course.getId(), edit);

        User newcomer = newStudentUser();
        assertThatThrownBy(() -> courseCheckoutService.checkout(newcomer, course.getId(),
                CheckoutRequest.builder()
                        .planId(monthly)
                        .paymentMethod(PaymentMethodRequest.builder().name("A Learner").email("learner@manara.test").build())
                        .build()))
                .isInstanceOf(BusinessException.class)
                .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.SUBSCRIPTION_PLAN_RETIRED));
    }

    @Test
    @DisplayName("a retired plan disappears from every offer, instructor and learner alike")
    void aRetiredPlanIsNotOffered() {
        var course = subscriptionCourse();
        Long monthly = planNamed(course, "Monthly");
        subscribeTo(course.getId(), monthly);

        var edit = echoOf(asLoadedNow(course.getId()));
        edit.setSubscriptionPlans(edit.getSubscriptionPlans().stream()
                .filter(plan -> !plan.getId().equals(monthly))
                .toList());
        courseService.updateCourse(instructorUser, course.getId(), edit);

        assertThat(asLoadedNow(course.getId()).getSubscriptionPlans())
                .extracting(p -> p.getName()).containsExactly("Yearly");
        assertThat(detailsFor(subscriber, course.getId()).getCourse().getSubscriptionPlans())
                .extracting(p -> p.getName()).containsExactly("Yearly");
    }

    @Test
    @DisplayName("the course can leave SUBSCRIPTION for FREE while a live subscriber keeps their term")
    void subscriptionToFree() {
        var course = subscriptionCourse();
        Long monthly = planNamed(course, "Monthly");
        subscribeTo(course.getId(), monthly);
        var boughtExpiry = courseEntitlementRepository
                .findByCourseIdAndStudentId(course.getId(), studentProfileOf(subscriber).getId())
                .orElseThrow().getExpiresAt();

        var edit = echoOf(asLoadedNow(course.getId()));
        edit.setAccessType(CourseAccessType.FREE);
        edit.setSubscriptionPlans(List.of());
        courseService.updateCourse(instructorUser, course.getId(), edit);

        assertThat(reload(course.getId()).getAccessType()).isEqualTo(CourseAccessType.FREE);
        // Both plans are gone from the offer; the bought one is still a row, the unsold one is not.
        assertThat(subscriptionPlanRepository.findById(monthly)).get()
                .satisfies(plan -> assertThat(plan.isActive()).isFalse());
        assertThat(asLoadedNow(course.getId()).getSubscriptionPlans()).isEmpty();

        var entitlement = courseEntitlementRepository
                .findByCourseIdAndStudentId(course.getId(), studentProfileOf(subscriber).getId())
                .orElseThrow();
        assertThat(entitlement.getExpiresAt()).isEqualTo(boughtExpiry);
        assertThat(entitlement.getSubscriptionPlan().getId()).isEqualTo(monthly);
        assertThat(entitlement.isActiveAt(LocalDateTime.now())).isTrue();
    }

    @Test
    @DisplayName("the course can move to PURCHASE with the same guarantees")
    void subscriptionToPurchase() {
        var course = subscriptionCourse();
        Long monthly = planNamed(course, "Monthly");
        subscribeTo(course.getId(), monthly);

        var edit = echoOf(asLoadedNow(course.getId()));
        edit.setAccessType(CourseAccessType.PURCHASE);
        edit.setPurchasePrice(new BigDecimal("450.00"));
        edit.setSubscriptionPlans(List.of());
        courseService.updateCourse(instructorUser, course.getId(), edit);

        assertThat(reload(course.getId()).getAccessType()).isEqualTo(CourseAccessType.PURCHASE);
        assertThat(subscriptionPlanRepository.findById(monthly)).isPresent();
        assertThat(courseSubscriptionRepository.findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(
                course.getId(), studentProfileOf(subscriber).getId())).isPresent();
    }

    /**
     * A plan nobody has bought is only ever an offer, so withdrawing it deletes the row.
     *
     * <p>Retiring everything instead would have been the easy repair, and it would leave the
     * database accumulating rows for offers that were typed and removed the same afternoon.
     */
    @Test
    @DisplayName("a plan nobody bought is deleted outright, not retired")
    void anUnsoldPlanIsStillDeleted() {
        var course = subscriptionCourse();
        Long yearly = planNamed(course, "Yearly");

        var edit = echoOf(asLoadedNow(course.getId()));
        edit.setSubscriptionPlans(edit.getSubscriptionPlans().stream()
                .filter(plan -> !plan.getId().equals(yearly))
                .toList());
        courseService.updateCourse(instructorUser, course.getId(), edit);

        assertThat(subscriptionPlanRepository.findById(yearly)).isEmpty();
    }

    /**
     * The behaviour that already worked, asserted here so retirement cannot quietly break it.
     *
     * <p>Editing a plan changes the offer, not the contract: the subscriber's expiry and the price
     * they paid are both copied onto their own rows at purchase and are never re-derived.
     */
    @Test
    @DisplayName("renaming and re-pricing a held plan still leaves the bought term alone")
    void editingAHeldPlanDoesNotTouchTheContract() {
        var course = subscriptionCourse();
        Long monthly = planNamed(course, "Monthly");
        subscribeTo(course.getId(), monthly);
        var studentId = studentProfileOf(subscriber).getId();
        var before = courseSubscriptionRepository
                .findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(course.getId(), studentId)
                .orElseThrow();

        var edit = echoOf(asLoadedNow(course.getId()));
        var renamed = edit.getSubscriptionPlans().stream()
                .filter(plan -> plan.getId().equals(monthly)).findFirst().orElseThrow();
        renamed.setName("Monthly, renamed");
        renamed.setPrice(new BigDecimal("175.00"));
        renamed.setDuration(3);
        courseService.updateCourse(instructorUser, course.getId(), edit);

        var after = courseSubscriptionRepository
                .findFirstByCourseIdAndStudentIdOrderByExpiresAtDesc(course.getId(), studentId)
                .orElseThrow();
        assertThat(after.getPricePaid()).isEqualByComparingTo(before.getPricePaid());
        assertThat(after.getExpiresAt()).isEqualTo(before.getExpiresAt());
        assertThat(courseEntitlementRepository.findByCourseIdAndStudentId(course.getId(), studentId))
                .get().satisfies(entitlement ->
                        assertThat(entitlement.getExpiresAt()).isEqualTo(before.getExpiresAt()));
    }
}
