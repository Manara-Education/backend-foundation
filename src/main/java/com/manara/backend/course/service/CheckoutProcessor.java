package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.CheckoutResponse;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.mapper.EntitlementMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.CoursePurchase;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionStatus;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.CoursePurchaseRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.model.PaymentReceipt;
import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The one transaction that turns a checkout into access.
 *
 * <p>Whatever path it takes — free, purchase, subscribe, renew — it ends with the same three facts
 * consistent with each other: an {@link Enrollment} saying the learner joined, a
 * {@link CourseEntitlement} saying what they may open and until when, and, for a subscription, a
 * {@link CourseSubscription} recording what was bought. They are written together or not at all.
 *
 * <p><strong>Nothing in the request decides what is charged.</strong> A purchase costs the course's
 * stored price; a subscription costs the stored plan's price, after the plan has been confirmed to
 * belong to this course. The request carries an identifier and an instrument, and nothing else the
 * server acts on.
 *
 * <p><strong>A later price change reaches none of it.</strong> What a learner may open is
 * {@link CourseEntitlement}, a standing grant that is never re-read against the course's current
 * price; that they joined is {@link Enrollment}, which nothing here rewrites; and what they were
 * charged is on their own {@link CoursePurchase} or {@link CourseSubscription} row rather than
 * derived from the course. Repricing therefore cannot charge a difference, cancel access, or
 * require a repurchase — it decides what the next buyer pays and nothing else.
 *
 * <p>Repeat calls are safe by construction. The first thing this does is take a write lock on the
 * learner's entitlement row; if the access it would grant is already open, it returns the current
 * state and charges nothing. A double-clicked purchase therefore cannot buy the same course twice,
 * and a renewal cannot be paid for twice. The case the lock cannot cover — two concurrent
 * <em>first</em> checkouts, with no row yet to lock — is settled by the unique constraints on
 * {@code enrollments} and {@code course_entitlements} and retried by {@link CourseCheckoutService}.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckoutProcessor {

    private final CourseRepository courseRepository;
    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseEntitlementRepository courseEntitlementRepository;
    private final CoursePurchaseRepository coursePurchaseRepository;
    private final CourseSubscriptionRepository courseSubscriptionRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final CourseMapper courseMapper;
    private final EntitlementMapper entitlementMapper;
    private final EntitlementPolicy entitlementPolicy;
    private final SubscriptionWindow subscriptionWindow;
    private final PaymentGateway paymentGateway;
    private final Clock clock;

    /**
     * The platform prices in Egyptian pounds and nothing configures otherwise. Named rather than
     * inlined so the day a second currency appears there is one place that has to answer for it —
     * and so the rows written before that day say what they meant.
     */
    private static final String CURRENCY = "EGP";

    @Transactional
    public CheckoutResponse checkout(User user, Long courseId, CheckoutRequest request) {
        Student student = requireStudent(user);
        Course course = requirePublishedCourse(courseId);
        LocalDateTime now = LocalDateTime.now(clock);

        CourseEntitlement entitlement =
                courseEntitlementRepository.findForUpdate(courseId, student.getId()).orElse(null);

        // Already open: this is a repeat of a checkout that succeeded. Same answer, no charge.
        if (entitlement != null && entitlement.isActiveAt(now)) {
            return respond(course, student, null);
        }

        PaymentReceipt receipt = switch (course.getAccessType()) {
            case FREE -> grantFree(course, student, entitlement, now);
            case PURCHASE -> grantPurchase(course, student, entitlement, request, now);
            case SUBSCRIPTION -> grantSubscription(course, student, entitlement, request, now);
        };

        ensureEnrolled(course, student);
        return respond(course, student, receipt);
    }

    // --- the three paths -----------------------------------------------------

    private PaymentReceipt grantFree(
            Course course, Student student, CourseEntitlement existing, LocalDateTime now) {
        // No payment, no card, no plan. Enrolling is the whole transaction.
        upsertPerpetual(course, student, existing, EntitlementSource.FREE, now);
        return null;
    }

    private PaymentReceipt grantPurchase(
            Course course, Student student, CourseEntitlement existing,
            CheckoutRequest request, LocalDateTime now) {

        BigDecimal price = course.getPurchasePrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            // A purchase course without a price is a misconfigured course, not a free one.
            throw new BusinessException("error.course.purchasePriceRequired");
        }

        PaymentReceipt receipt = paymentGateway.charge(
                new PaymentCharge(price, course.getTitle(), idempotencyKey(course, student, "purchase")),
                paymentMethodOf(request));

        // Written in the same transaction as the entitlement it paid for, so a charge can never
        // grant access without leaving a record of itself, and a rolled-back grant can never leave
        // a receipt for access nobody has. Until this row existed the purchase path kept nothing at
        // all, and repricing the course destroyed the only remaining evidence of what its existing
        // buyers were charged.
        coursePurchaseRepository.save(CoursePurchase.builder()
                .course(course)
                .student(student)
                .listPrice(price)
                .amountPaid(receipt.amount())
                .currency(CURRENCY)
                .paymentReference(receipt.reference())
                .purchasedAt(receipt.paidAt())
                .build());

        upsertPerpetual(course, student, existing, EntitlementSource.PURCHASE, now);
        return receipt;
    }

    private PaymentReceipt grantSubscription(
            Course course, Student student, CourseEntitlement existing,
            CheckoutRequest request, LocalDateTime now) {

        SubscriptionPlan plan = requirePlanOfCourse(course, request);

        // The window is computed from the stored plan, never from anything the client sent. Renewing
        // an entitlement that is still open extends it from its own end rather than from now.
        LocalDateTime startsAt = subscriptionWindow.startOf(
                now, existing == null ? null : existing.getExpiresAt());
        LocalDateTime expiresAt = subscriptionWindow.endOf(startsAt, plan);

        PaymentReceipt receipt = paymentGateway.charge(
                new PaymentCharge(plan.getPrice(), course.getTitle() + " - " + plan.getName(),
                        idempotencyKey(course, student, "plan-" + plan.getId())),
                paymentMethodOf(request));

        closeOpenTerms(course, student);
        courseSubscriptionRepository.save(
                entitlementMapper.toSubscription(course, student, plan, startsAt, expiresAt, receipt));

        if (existing == null) {
            courseEntitlementRepository.save(entitlementMapper.toSubscriptionEntitlement(
                    course, student, plan, startsAt, expiresAt));
        } else {
            // The entitlement is the learner's *current* standing, so renewal moves this row forward
            // rather than adding a second one. What was bought is kept in course_subscriptions.
            existing.setSource(EntitlementSource.SUBSCRIPTION);
            existing.setSubscriptionPlan(plan);
            existing.setStartsAt(startsAt);
            existing.setExpiresAt(expiresAt);
            courseEntitlementRepository.save(existing);
        }
        return receipt;
    }

    // --- shared steps --------------------------------------------------------

    private void upsertPerpetual(
            Course course, Student student, CourseEntitlement existing,
            EntitlementSource source, LocalDateTime now) {

        if (existing == null) {
            courseEntitlementRepository.save(
                    entitlementMapper.toPerpetualEntitlement(course, student, source, now));
            return;
        }
        // Reached when a course was switched from SUBSCRIPTION to FREE or PURCHASE after this
        // learner's window lapsed: what they hold now is perpetual, so the end date goes away.
        existing.setSource(source);
        existing.setSubscriptionPlan(null);
        existing.setStartsAt(now);
        existing.setExpiresAt(null);
        courseEntitlementRepository.save(existing);
    }

    /**
     * Joining is idempotent and never repeated: a learner whose subscription lapsed and who renews
     * keeps the enrolment they already had, and with it their progress.
     */
    private void ensureEnrolled(Course course, Student student) {
        if (enrollmentRepository.existsByCourseIdAndStudentId(course.getId(), student.getId())) {
            return;
        }
        enrollmentRepository.save(courseMapper.toEnrollment(course, student));
        course.setStudentsCount(course.getStudentsCount() + 1);
        courseRepository.save(course);
    }

    private void closeOpenTerms(Course course, Student student) {
        List<CourseSubscription> open = courseSubscriptionRepository
                .findByCourseIdAndStudentIdAndStatus(course.getId(), student.getId(), SubscriptionStatus.ACTIVE);
        for (CourseSubscription subscription : open) {
            subscription.setStatus(SubscriptionStatus.EXPIRED);
        }
        courseSubscriptionRepository.saveAll(open);
    }

    private CheckoutResponse respond(Course course, Student student, PaymentReceipt receipt) {
        // Flushed first so the access read below sees the rows this transaction just wrote.
        courseEntitlementRepository.flush();
        enrollmentRepository.flush();

        Enrollment enrollment = enrollmentRepository
                .findByCourseIdAndStudentId(course.getId(), student.getId())
                .orElse(null);

        return entitlementMapper.toCheckoutResponse(
                course,
                enrollment == null ? null : enrollment.getId(),
                entitlementPolicy.accessOf(course.getId(), student),
                receipt);
    }

    // --- validation ----------------------------------------------------------

    /**
     * A plan is only ever trusted after it is shown to belong to the course being bought. Without
     * this, a learner could pay a one-day plan's price for another course's yearly access.
     */
    private SubscriptionPlan requirePlanOfCourse(Course course, CheckoutRequest request) {
        Long planId = request == null ? null : request.getPlanId();
        if (planId == null) {
            throw new BusinessException("error.course.planRequired");
        }
        SubscriptionPlan plan = subscriptionPlanRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.planNotFound", planId.toString()));
        if (!plan.getCourse().getId().equals(course.getId())) {
            throw new BusinessException("error.course.planNotInCourse", planId.toString());
        }
        // A retired plan is still a real row — every subscriber who bought it still points at it —
        // and it is no longer for sale. Refused by name rather than by absence, so a learner whose
        // renewal screen was rendered before the instructor withdrew the plan is told what happened
        // instead of being told their own plan does not exist.
        if (!plan.isActive()) {
            throw new BusinessException(ErrorCode.SUBSCRIPTION_PLAN_RETIRED,
                    "error.course.planRetired", planId.toString());
        }
        return plan;
    }

    /**
     * Resolves the payment instrument from the request.
     *
     * <p>The flat top-level card fields the previous contract accepted are gone along with the
     * card fields themselves — see {@link PaymentMethodRequest}. Only the nested object remains,
     * which is the shape the client already sends.
     */
    private PaymentMethodRequest paymentMethodOf(CheckoutRequest request) {
        if (request == null) {
            throw new BusinessException("error.payment.required");
        }
        return request.getPaymentMethod();
    }

    private Student requireStudent(User user) {
        if (user == null || user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }
        return studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "error.profile.studentNotFound", user.getId().toString()));
    }

    /** Drafts are indistinguishable from a missing course on the learner side. */
    private Course requirePublishedCourse(Long courseId) {
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (course.getStatus() != CourseStatus.PUBLISHED) {
            throw new ResourceNotFoundException("error.course.notFound", courseId.toString());
        }
        return course;
    }

    /**
     * Stable across retries of the same intent, so the day a real gateway replaces the simulator it
     * can recognise a repeat instead of taking the money twice.
     */
    private String idempotencyKey(Course course, Student student, String purpose) {
        return "course-%d:student-%d:%s".formatted(course.getId(), student.getId(), purpose);
    }
}
