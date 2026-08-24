package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.CheckoutResponse;
import com.manara.backend.course.dto.CourseAccessResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionStatus;
import com.manara.backend.course.service.CourseAccess;
import com.manara.backend.payment.model.PaymentReceipt;
import com.manara.backend.profile.model.Student;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Builds the access-lifecycle rows and the shapes that describe them.
 *
 * <p>Pure, like every mapper here: the window it writes is handed in already computed, because
 * deciding when a subscription ends is a business rule and belongs to the service that owns it.
 */
@Component
public class EntitlementMapper {

    /** A grant with no end — a free enrolment or an outright purchase. */
    public CourseEntitlement toPerpetualEntitlement(
            Course course, Student student, EntitlementSource source, LocalDateTime startsAt) {
        return CourseEntitlement.builder()
                .course(course)
                .student(student)
                .source(source)
                .startsAt(startsAt)
                .expiresAt(null)
                .build();
    }

    public CourseEntitlement toSubscriptionEntitlement(
            Course course, Student student, SubscriptionPlan plan,
            LocalDateTime startsAt, LocalDateTime expiresAt) {
        return CourseEntitlement.builder()
                .course(course)
                .student(student)
                .source(EntitlementSource.SUBSCRIPTION)
                .subscriptionPlan(plan)
                .startsAt(startsAt)
                .expiresAt(expiresAt)
                .build();
    }

    public CourseSubscription toSubscription(
            Course course, Student student, SubscriptionPlan plan,
            LocalDateTime startsAt, LocalDateTime expiresAt, PaymentReceipt receipt) {
        return CourseSubscription.builder()
                .course(course)
                .student(student)
                .plan(plan)
                .startsAt(startsAt)
                .expiresAt(expiresAt)
                .status(SubscriptionStatus.ACTIVE)
                // The price is copied off the plan rather than referenced through it: repricing the
                // plan later must not rewrite what this learner was charged.
                .pricePaid(plan.getPrice())
                .paymentReference(receipt == null ? null : receipt.reference())
                .build();
    }

    public CourseAccessResponse toCourseAccessResponse(CourseAccess access) {
        return CourseAccessResponse.builder()
                .enrolled(access.enrolled())
                .entitled(access.entitled())
                .source(access.source())
                .status(access.status())
                .startsAt(access.startsAt())
                .expiresAt(access.expiresAt())
                .daysRemaining(access.daysRemaining())
                .planId(access.planId())
                .build();
    }

    public CheckoutResponse toCheckoutResponse(
            Course course, Long enrollmentId, CourseAccess access, PaymentReceipt receipt) {
        return CheckoutResponse.builder()
                .enrollmentId(enrollmentId)
                .courseId(course.getId())
                .accessType(course.getAccessType())
                .access(toCourseAccessResponse(access))
                .paymentReference(receipt == null ? null : receipt.reference())
                .build();
    }
}
