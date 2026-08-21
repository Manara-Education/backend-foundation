package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.mapper.EntitlementMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionStatus;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.payment.dto.PaymentMethodRequest;
import com.manara.backend.payment.model.PaymentCharge;
import com.manara.backend.payment.model.PaymentReceipt;
import com.manara.backend.payment.service.PaymentGateway;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * What a checkout is allowed to do, and what it must never do.
 *
 * <p>Two rules run through every case below. Money is only ever computed from stored figures — the
 * course's price, the plan's price — so no payload can talk the server into a cheaper charge. And a
 * repeated call is a repeat, not a second purchase: the same click twice, or a retried request, ends
 * with one enrolment, one entitlement and one charge.
 */
@ExtendWith(MockitoExtension.class)
class CheckoutProcessorTest {

    private static final Long COURSE_ID = 7L;
    private static final Long STUDENT_ID = 20L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);

    @Mock
    private CourseRepository courseRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private EnrollmentRepository enrollmentRepository;
    @Mock
    private CourseEntitlementRepository courseEntitlementRepository;
    @Mock
    private CourseSubscriptionRepository courseSubscriptionRepository;
    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock
    private EntitlementPolicy entitlementPolicy;
    @Mock
    private PaymentGateway paymentGateway;

    private CheckoutProcessor checkoutProcessor;

    private final User studentUser = User.builder().id(2L).role(Role.STUDENT).build();
    private final User instructorUser = User.builder().id(1L).role(Role.INSTRUCTOR).build();
    private final Student student = Student.builder().id(STUDENT_ID).user(studentUser).build();

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        checkoutProcessor = new CheckoutProcessor(
                courseRepository, studentRepository, enrollmentRepository, courseEntitlementRepository,
                courseSubscriptionRepository, subscriptionPlanRepository, new CourseMapper(),
                new EntitlementMapper(), entitlementPolicy, new SubscriptionWindow(), paymentGateway, fixed);

        lenient().when(entitlementPolicy.accessOf(any(Long.class), any(Student.class)))
                .thenReturn(CourseAccess.none());
        lenient().when(enrollmentRepository.findByCourseIdAndStudentId(COURSE_ID, STUDENT_ID))
                .thenReturn(Optional.of(Enrollment.builder().id(40L).build()));
    }

    // --- FREE ----------------------------------------------------------------

    @Test
    void enrollingInAFreeCourseTakesNoPaymentAndGrantsPerpetualAccess() {
        givenCourse(CourseAccessType.FREE, null);
        givenNoExistingAccess();

        checkoutProcessor.checkout(studentUser, COURSE_ID, new CheckoutRequest());

        verify(paymentGateway, never()).charge(any(), any());
        CourseEntitlement saved = savedEntitlement();
        assertThat(saved.getSource()).isEqualTo(EntitlementSource.FREE);
        assertThat(saved.getExpiresAt()).isNull();
        verify(enrollmentRepository).save(any(Enrollment.class));
    }

    /** An empty body is the whole contract for a free course. Nothing else is required or read. */
    @Test
    void aFreeCourseAcceptsAnAbsentBody() {
        givenCourse(CourseAccessType.FREE, null);
        givenNoExistingAccess();

        checkoutProcessor.checkout(studentUser, COURSE_ID, null);

        verify(courseEntitlementRepository).save(any(CourseEntitlement.class));
    }

    /**
     * The double-click case. The second call finds the access it granted and stops there — no second
     * enrolment, no second row, and for a paid course no second charge.
     */
    @Test
    void aSecondCheckoutOfAnAlreadyEntitledCourseChangesNothing() {
        Course course = givenCourse(CourseAccessType.PURCHASE, BigDecimal.valueOf(490));
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(courseEntitlementRepository.findForUpdate(COURSE_ID, STUDENT_ID))
                .willReturn(Optional.of(perpetual(course, EntitlementSource.PURCHASE)));

        var response = checkoutProcessor.checkout(studentUser, COURSE_ID, purchaseRequest());

        verify(paymentGateway, never()).charge(any(), any());
        verify(courseEntitlementRepository, never()).save(any());
        verify(enrollmentRepository, never()).save(any());
        assertThat(response.getPaymentReference()).isNull();
    }

    // --- PURCHASE ------------------------------------------------------------

    @Test
    void buyingACourseChargesItsStoredPriceAndGrantsPerpetualAccess() {
        givenCourse(CourseAccessType.PURCHASE, BigDecimal.valueOf(490));
        givenNoExistingAccess();
        givenPaymentAccepted();

        var response = checkoutProcessor.checkout(studentUser, COURSE_ID, purchaseRequest());

        assertThat(chargedAmount()).isEqualByComparingTo("490");
        CourseEntitlement saved = savedEntitlement();
        assertThat(saved.getSource()).isEqualTo(EntitlementSource.PURCHASE);
        assertThat(saved.getExpiresAt()).isNull();
        assertThat(response.getPaymentReference()).isEqualTo("sim_test");
    }

    /** The previous flat card payload still works, so a client deployed against it keeps buying. */
    @Test
    void theLegacyFlatCardFieldsAreStillAccepted() {
        givenCourse(CourseAccessType.PURCHASE, BigDecimal.valueOf(490));
        givenNoExistingAccess();
        givenPaymentAccepted();

        var legacy = new CheckoutRequest();
        legacy.setCardNumber("4242 4242 4242 4242");
        legacy.setExpiry("12 / 30");
        legacy.setCvc("123");
        legacy.setName("Learner");

        checkoutProcessor.checkout(studentUser, COURSE_ID, legacy);

        var method = ArgumentCaptor.forClass(PaymentMethodRequest.class);
        verify(paymentGateway).charge(any(), method.capture());
        assertThat(method.getValue().getCardNumber()).isEqualTo("4242 4242 4242 4242");
    }

    /**
     * Sending no instrument at all is a different failure from sending a bad one, and saying
     * "invalid card number" would send the learner hunting for a typo that is not there.
     */
    @Test
    void buyingWithNoInstrumentAtAllSaysSo() {
        givenCourse(CourseAccessType.PURCHASE, BigDecimal.valueOf(490));
        givenNoExistingAccess();
        given(paymentGateway.charge(any(), isNull()))
                .willThrow(new BusinessException("error.payment.required"));

        assertThatThrownBy(() -> checkoutProcessor.checkout(studentUser, COURSE_ID, new CheckoutRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.payment.required");
    }

    @Test
    void aPurchaseCourseWithNoPriceIsRefusedRatherThanGivenAway() {
        givenCourse(CourseAccessType.PURCHASE, null);
        givenNoExistingAccess();

        assertThatThrownBy(() -> checkoutProcessor.checkout(studentUser, COURSE_ID, purchaseRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.purchasePriceRequired");
        verify(paymentGateway, never()).charge(any(), any());
    }

    // --- SUBSCRIPTION --------------------------------------------------------

    @Test
    void subscribingChargesThePlansOwnPriceAndOpensItsWindow() {
        Course course = givenCourse(CourseAccessType.SUBSCRIPTION, null);
        givenNoExistingAccess();
        givenPlan(course, SubscriptionUnit.MONTH, 3, BigDecimal.valueOf(600));
        givenPaymentAccepted();

        checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L));

        assertThat(chargedAmount()).isEqualByComparingTo("600");

        CourseEntitlement entitlement = savedEntitlement();
        assertThat(entitlement.getSource()).isEqualTo(EntitlementSource.SUBSCRIPTION);
        assertThat(entitlement.getStartsAt()).isEqualTo(NOW);
        assertThat(entitlement.getExpiresAt()).isEqualTo(NOW.plusMonths(3));

        CourseSubscription subscription = savedSubscription();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getExpiresAt()).isEqualTo(NOW.plusMonths(3));
        assertThat(subscription.getPricePaid()).isEqualByComparingTo("600");
        assertThat(subscription.getPaymentReference()).isEqualTo("sim_test");
    }

    @Test
    void aDayPlanOpensADayWindow() {
        Course course = givenCourse(CourseAccessType.SUBSCRIPTION, null);
        givenNoExistingAccess();
        givenPlan(course, SubscriptionUnit.DAY, 30, BigDecimal.valueOf(250));
        givenPaymentAccepted();

        checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L));

        assertThat(savedEntitlement().getExpiresAt()).isEqualTo(NOW.plusDays(30));
    }

    @Test
    void aWeekPlanOpensAWeekWindow() {
        Course course = givenCourse(CourseAccessType.SUBSCRIPTION, null);
        givenNoExistingAccess();
        givenPlan(course, SubscriptionUnit.WEEK, 2, BigDecimal.valueOf(150));
        givenPaymentAccepted();

        checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L));

        assertThat(savedEntitlement().getExpiresAt()).isEqualTo(NOW.plusWeeks(2));
    }

    @Test
    void subscribingWithoutChoosingAPlanIsRefused() {
        givenCourse(CourseAccessType.SUBSCRIPTION, null);
        givenNoExistingAccess();

        assertThatThrownBy(() -> checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.planRequired");
        verify(paymentGateway, never()).charge(any(), any());
    }

    @Test
    void anUnknownPlanIsRefused() {
        givenCourse(CourseAccessType.SUBSCRIPTION, null);
        givenNoExistingAccess();
        given(subscriptionPlanRepository.findById(404L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(404L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.course.planNotFound");
        verify(paymentGateway, never()).charge(any(), any());
    }

    /**
     * The attack this closes: pay a cheap course's one-week plan and be handed a year of an
     * expensive one. A plan id is only trusted once it is shown to belong to the course being bought.
     */
    @Test
    void aPlanBelongingToAnotherCourseIsRefused() {
        givenCourse(CourseAccessType.SUBSCRIPTION, null);
        givenNoExistingAccess();

        Course otherCourse = Course.builder().id(999L).title("Another course").build();
        given(subscriptionPlanRepository.findById(99L)).willReturn(Optional.of(SubscriptionPlan.builder()
                .id(99L).name("Yearly").unit(SubscriptionUnit.MONTH).duration(12)
                .price(BigDecimal.ONE).orderIndex(0).course(otherCourse).build()));

        assertThatThrownBy(() -> checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.planNotInCourse");
        verify(paymentGateway, never()).charge(any(), any());
    }

    // --- renewal -------------------------------------------------------------

    @Test
    void renewingAfterExpiryMovesTheSameEntitlementForwardAndKeepsTheEnrolment() {
        Course course = givenCourse(CourseAccessType.SUBSCRIPTION, null);
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        CourseEntitlement lapsed = subscription(course, NOW.minusDays(40), NOW.minusDays(10));
        given(courseEntitlementRepository.findForUpdate(COURSE_ID, STUDENT_ID)).willReturn(Optional.of(lapsed));
        given(enrollmentRepository.existsByCourseIdAndStudentId(COURSE_ID, STUDENT_ID)).willReturn(true);
        givenPlan(course, SubscriptionUnit.MONTH, 1, BigDecimal.valueOf(250));
        givenPaymentAccepted();

        checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L));

        // The lapsed window is gone; the same row now carries the new one.
        assertThat(lapsed.getStartsAt()).isEqualTo(NOW);
        assertThat(lapsed.getExpiresAt()).isEqualTo(NOW.plusMonths(1));
        verify(courseEntitlementRepository).save(lapsed);
        // Their enrolment — and with it their progress — is untouched.
        verify(enrollmentRepository, never()).save(any());
    }

    /**
     * A subscription that is still open is not re-bought, however the request arrived — the same
     * guarantee that makes a double-clicked purchase safe. Extending an open window from its own end
     * rather than from today is {@link SubscriptionWindow}'s rule and is covered there.
     */
    @Test
    void aStillOpenSubscriptionIsNotChargedAgain() {
        Course course = givenCourse(CourseAccessType.SUBSCRIPTION, null);
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(courseEntitlementRepository.findForUpdate(COURSE_ID, STUDENT_ID))
                .willReturn(Optional.of(subscription(course, NOW.minusDays(20), NOW.plusDays(5))));

        var response = checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L));

        verify(paymentGateway, never()).charge(any(), any());
        verify(courseSubscriptionRepository, never()).save(any());
        assertThat(response.getPaymentReference()).isNull();
    }

    @Test
    void renewingClosesTheTermItReplaces() {
        Course course = givenCourse(CourseAccessType.SUBSCRIPTION, null);
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(courseEntitlementRepository.findForUpdate(COURSE_ID, STUDENT_ID))
                .willReturn(Optional.of(subscription(course, NOW.minusDays(40), NOW.minusDays(10))));
        given(enrollmentRepository.existsByCourseIdAndStudentId(COURSE_ID, STUDENT_ID)).willReturn(true);
        givenPlan(course, SubscriptionUnit.MONTH, 1, BigDecimal.valueOf(250));
        givenPaymentAccepted();

        CourseSubscription previous = CourseSubscription.builder()
                .id(5L).status(SubscriptionStatus.ACTIVE).expiresAt(NOW.minusDays(10)).build();
        given(courseSubscriptionRepository.findByCourseIdAndStudentIdAndStatus(
                COURSE_ID, STUDENT_ID, SubscriptionStatus.ACTIVE)).willReturn(List.of(previous));

        checkoutProcessor.checkout(studentUser, COURSE_ID, subscriptionRequest(99L));

        assertThat(previous.getStatus()).isEqualTo(SubscriptionStatus.EXPIRED);
    }

    // --- who may check out ---------------------------------------------------

    @Test
    void anInstructorCannotCheckOut() {
        assertThatThrownBy(() -> checkoutProcessor.checkout(instructorUser, COURSE_ID, new CheckoutRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.onlyStudent");
    }

    @Test
    void aDraftCourseCannotBeCheckedOut() {
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(Course.builder()
                .id(COURSE_ID).title("Draft").status(CourseStatus.DRAFT)
                .accessType(CourseAccessType.FREE)
                .instructor(Instructor.builder().id(10L).user(instructorUser).build())
                .build()));

        assertThatThrownBy(() -> checkoutProcessor.checkout(studentUser, COURSE_ID, new CheckoutRequest()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.course.notFound");
    }

    // --- fixtures ------------------------------------------------------------

    private Course givenCourse(CourseAccessType accessType, BigDecimal purchasePrice) {
        Course course = Course.builder()
                .id(COURSE_ID)
                .title("Course")
                .status(CourseStatus.PUBLISHED)
                .accessType(accessType)
                .purchasePrice(purchasePrice)
                .studentsCount(3)
                .instructor(Instructor.builder().id(10L).user(instructorUser).build())
                .build();
        given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));
        return course;
    }

    private void givenNoExistingAccess() {
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(courseEntitlementRepository.findForUpdate(COURSE_ID, STUDENT_ID)).willReturn(Optional.empty());
        lenient().when(enrollmentRepository.existsByCourseIdAndStudentId(COURSE_ID, STUDENT_ID))
                .thenReturn(false);
    }

    private void givenPlan(Course course, SubscriptionUnit unit, int duration, BigDecimal price) {
        given(subscriptionPlanRepository.findById(99L)).willReturn(Optional.of(SubscriptionPlan.builder()
                .id(99L).name(duration + " " + unit).unit(unit).duration(duration)
                .price(price).orderIndex(0).course(course).build()));
    }

    private void givenPaymentAccepted() {
        given(paymentGateway.charge(any(), any()))
                .willReturn(new PaymentReceipt("sim_test", BigDecimal.ONE, NOW));
    }

    private BigDecimal chargedAmount() {
        var charge = ArgumentCaptor.forClass(PaymentCharge.class);
        verify(paymentGateway).charge(charge.capture(), any());
        return charge.getValue().amount();
    }

    private CourseEntitlement savedEntitlement() {
        var captor = ArgumentCaptor.forClass(CourseEntitlement.class);
        verify(courseEntitlementRepository).save(captor.capture());
        return captor.getValue();
    }

    private CourseSubscription savedSubscription() {
        var captor = ArgumentCaptor.forClass(CourseSubscription.class);
        verify(courseSubscriptionRepository).save(captor.capture());
        return captor.getValue();
    }

    private CheckoutRequest purchaseRequest() {
        return CheckoutRequest.builder().paymentMethod(card()).build();
    }

    private CheckoutRequest subscriptionRequest(Long planId) {
        return CheckoutRequest.builder().planId(planId).paymentMethod(card()).build();
    }

    private PaymentMethodRequest card() {
        return PaymentMethodRequest.builder()
                .cardNumber("4242424242424242").expiry("12 / 30").cvc("123").name("Learner").build();
    }

    private CourseEntitlement perpetual(Course course, EntitlementSource source) {
        return CourseEntitlement.builder()
                .id(1L).course(course).student(student).source(source)
                .startsAt(NOW.minusDays(5)).expiresAt(null).build();
    }

    private CourseEntitlement subscription(Course course, LocalDateTime startsAt, LocalDateTime expiresAt) {
        return CourseEntitlement.builder()
                .id(1L).course(course).student(student).source(EntitlementSource.SUBSCRIPTION)
                .startsAt(startsAt).expiresAt(expiresAt).build();
    }
}
