package com.manara.backend.course.service;

import com.manara.backend.course.model.AccessStatus;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * The one place "may this learner open this course" is decided.
 *
 * <p>Three sources of valid access and one shape of answer. The cases that matter are the ones the
 * old enrolment-is-access model could not express at all: a window that has closed, and a window
 * that is about to.
 */
@ExtendWith(MockitoExtension.class)
class EntitlementPolicyTest {

    private static final Long COURSE_ID = 7L;
    private static final Long STUDENT_ID = 20L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 21, 12, 0);

    @Mock
    private CourseEntitlementRepository courseEntitlementRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private StudentRepository studentRepository;

    private EntitlementPolicy entitlementPolicy;

    private final User studentUser = User.builder().id(2L).role(Role.STUDENT).build();
    private final Student student = Student.builder().id(STUDENT_ID).user(studentUser).build();

    @BeforeEach
    void setUp() {
        Clock fixed = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneId.of("UTC"));
        entitlementPolicy = new EntitlementPolicy(
                courseEntitlementRepository, enrollmentRepository, studentRepository, fixed);
    }

    @Test
    void aFreeGrantNeverExpires() {
        givenEnrolled();
        givenEntitlement(perpetual(EntitlementSource.FREE));

        var access = entitlementPolicy.accessOf(COURSE_ID, student);

        assertThat(access.entitled()).isTrue();
        assertThat(access.source()).isEqualTo(EntitlementSource.FREE);
        assertThat(access.status()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(access.expiresAt()).isNull();
        assertThat(access.daysRemaining()).isNull();
    }

    @Test
    void aPurchaseNeverExpires() {
        givenEnrolled();
        givenEntitlement(perpetual(EntitlementSource.PURCHASE));

        var access = entitlementPolicy.accessOf(COURSE_ID, student);

        assertThat(access.entitled()).isTrue();
        assertThat(access.status()).isEqualTo(AccessStatus.ACTIVE);
    }

    @Test
    void anOpenSubscriptionReportsItsRemainingDays() {
        givenEnrolled();
        givenEntitlement(subscriptionUntil(NOW.plusDays(23)));

        var access = entitlementPolicy.accessOf(COURSE_ID, student);

        assertThat(access.entitled()).isTrue();
        assertThat(access.status()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(access.daysRemaining()).isEqualTo(23);
        assertThat(access.planId()).isEqualTo(99L);
    }

    /** The warning threshold is a product rule, and it is decided here rather than by a screen. */
    @Test
    void aSubscriptionInsideTheWarningWindowIsExpiringSoon() {
        givenEnrolled();
        givenEntitlement(subscriptionUntil(NOW.plusDays(3)));

        var access = entitlementPolicy.accessOf(COURSE_ID, student);

        assertThat(access.entitled()).isTrue();
        assertThat(access.status()).isEqualTo(AccessStatus.EXPIRING_SOON);
        assertThat(access.daysRemaining()).isEqualTo(3);
    }

    /**
     * The row is still there and the enrolment is still there — which is exactly why access has to
     * be a date comparison rather than a lookup.
     */
    @Test
    void aClosedSubscriptionIsExpiredButStillEnrolled() {
        givenEnrolled();
        givenEntitlement(subscriptionUntil(NOW.minusMinutes(1)));

        var access = entitlementPolicy.accessOf(COURSE_ID, student);

        assertThat(access.entitled()).isFalse();
        assertThat(access.status()).isEqualTo(AccessStatus.EXPIRED);
        assertThat(access.enrolled()).isTrue();
        assertThat(access.expiresAt()).isEqualTo(NOW.minusMinutes(1));
        // The plan survives the lapse, so the renewal screen can offer the same one back.
        assertThat(access.planId()).isEqualTo(99L);
    }

    @Test
    void isEntitledAgreesWithTheDescribedAccess() {
        given(courseEntitlementRepository.findByCourseIdAndStudentId(COURSE_ID, STUDENT_ID))
                .willReturn(Optional.of(subscriptionUntil(NOW.minusDays(1))));

        assertThat(entitlementPolicy.isEntitled(COURSE_ID, STUDENT_ID)).isFalse();
    }

    @Test
    void aLearnerWithNoGrantHasNothing() {
        given(enrollmentRepository.existsByCourseIdAndStudentId(COURSE_ID, STUDENT_ID)).willReturn(false);
        given(courseEntitlementRepository.findByCourseIdAndStudentId(COURSE_ID, STUDENT_ID))
                .willReturn(Optional.empty());

        var access = entitlementPolicy.accessOf(COURSE_ID, student);

        assertThat(access.entitled()).isFalse();
        assertThat(access.enrolled()).isFalse();
        assertThat(access.status()).isEqualTo(AccessStatus.NONE);
        assertThat(access.source()).isNull();
    }

    /** An instructor has access through ownership, which is a different question and a different class. */
    @Test
    void aViewerWhoIsNotALearnerHasNoStandingToDescribe() {
        var access = entitlementPolicy.accessOf(
                User.builder().id(1L).role(Role.INSTRUCTOR).build(), COURSE_ID);

        assertThat(access.status()).isEqualTo(AccessStatus.NONE);
        assertThat(access.entitled()).isFalse();
    }

    // --- fixtures ------------------------------------------------------------

    private void givenEnrolled() {
        lenient().when(enrollmentRepository.existsByCourseIdAndStudentId(COURSE_ID, STUDENT_ID))
                .thenReturn(true);
    }

    private void givenEntitlement(CourseEntitlement entitlement) {
        given(courseEntitlementRepository.findByCourseIdAndStudentId(COURSE_ID, STUDENT_ID))
                .willReturn(Optional.of(entitlement));
    }

    private CourseEntitlement perpetual(EntitlementSource source) {
        return CourseEntitlement.builder()
                .id(1L)
                .student(student)
                .source(source)
                .startsAt(NOW.minusDays(30))
                .expiresAt(null)
                .build();
    }

    private CourseEntitlement subscriptionUntil(LocalDateTime expiresAt) {
        return CourseEntitlement.builder()
                .id(1L)
                .student(student)
                .source(EntitlementSource.SUBSCRIPTION)
                .subscriptionPlan(SubscriptionPlan.builder()
                        .id(99L).name("Monthly").duration(1)
                        .price(BigDecimal.valueOf(250)).orderIndex(0).build())
                .startsAt(NOW.minusDays(7))
                .expiresAt(expiresAt)
                .build();
    }
}
