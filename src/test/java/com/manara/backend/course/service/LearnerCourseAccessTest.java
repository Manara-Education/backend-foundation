package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

/**
 * Who may see a course, and how much of it.
 *
 * <p>The rule this pins down is the one the API used to get wrong: publication makes a course
 * <em>visible</em>, the learner's entitlement makes it <em>readable</em>. A signed-in stranger sees
 * the shape of a published course and none of its content — and so, now, does a learner whose
 * subscription ran out, except that they keep every figure describing what they already did.
 */
@ExtendWith(MockitoExtension.class)
class LearnerCourseAccessTest {

    private static final Long COURSE_ID = 7L;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private CourseAggregateLoader courseAggregateLoader;

    @Mock
    private CourseProgressionService courseProgressionService;

    @Mock
    private EntitlementPolicy entitlementPolicy;

    private LearnerCourseAccess learnerCourseAccess;

    private final User instructorUser = User.builder().id(1L).role(Role.INSTRUCTOR).build();
    private final User studentUser = User.builder().id(2L).role(Role.STUDENT).build();
    private final User otherStudentUser = User.builder().id(3L).role(Role.STUDENT).build();
    private final Student student = Student.builder().id(20L).user(studentUser).build();
    private final Student otherStudent = Student.builder().id(30L).user(otherStudentUser).build();

    @BeforeEach
    void setUp() {
        learnerCourseAccess = new LearnerCourseAccess(
                courseRepository, studentRepository, enrollmentRepository,
                courseAggregateLoader, courseProgressionService, entitlementPolicy,
                new CourseVisibility(courseRepository, studentRepository, enrollmentRepository));
    }

    // --- reading -------------------------------------------------------------

    @Test
    void aSignedInStrangerSeesAPublishedCourseWithEveryLessonLocked() {
        givenCourse(CourseStatus.PUBLISHED);
        given(studentRepository.findByUserId(3L)).willReturn(Optional.of(otherStudent));
        given(enrollmentRepository.findByCourseIdAndStudentId(COURSE_ID, 30L)).willReturn(Optional.empty());

        var viewer = learnerCourseAccess.resolveViewer(otherStudentUser, COURSE_ID);

        assertThat(viewer.isEnrolled()).isFalse();
        assertThat(viewer.progression().accessibleLessonIds()).isEmpty();
        assertThat(viewer.progression().tracksProgress()).isFalse();
    }

    @Test
    void anEnrolledLearnerGetsTheProgressionTheCurriculumGivesThem() {
        Course course = givenCourse(CourseStatus.PUBLISHED);
        givenEnrolled();
        givenEntitled(true);
        var progression = progressionFor(course);
        given(courseProgressionService.progressionOf(any(), any())).willReturn(progression);

        var viewer = learnerCourseAccess.resolveViewer(studentUser, COURSE_ID);

        assertThat(viewer.isEnrolled()).isTrue();
        assertThat(viewer.progression()).isSameAs(progression);
    }

    /**
     * The expiry rule, stated as plainly as it can be: the door shuts, the record stays. Anything
     * that dropped the completed lessons or reset the percentage here would be destroying a
     * learner's history to enforce a billing decision.
     */
    @Test
    void aLearnerWhoseSubscriptionLapsedKeepsTheirProgressAndLosesTheContent() {
        Course course = givenCourse(CourseStatus.PUBLISHED);
        givenEnrolled();
        givenEntitled(false);
        given(courseProgressionService.progressionOf(any(), any())).willReturn(completedProgressionFor(course));

        var viewer = learnerCourseAccess.resolveViewer(studentUser, COURSE_ID);

        assertThat(viewer.isEnrolled()).isTrue();
        assertThat(viewer.progression().accessibleLessonIds()).isEmpty();
        assertThat(viewer.progression().nextLessonId()).isNull();
        // Everything they earned survives the lapse.
        assertThat(viewer.progression().tracksProgress()).isTrue();
        assertThat(viewer.progression().completedLessonIds()).containsExactly(1L);
        assertThat(viewer.progression().progress()).isEqualTo(100);
    }

    @Test
    void aDraftCourseDoesNotExistForAnyoneButItsInstructor() {
        givenCourse(CourseStatus.DRAFT);
        lenient().when(studentRepository.findByUserId(2L)).thenReturn(Optional.of(student));

        assertThatThrownBy(() -> learnerCourseAccess.resolveViewer(studentUser, COURSE_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.course.notFound");
    }

    @Test
    void theOwningInstructorReadsTheirOwnDraftInFull() {
        givenCourse(CourseStatus.DRAFT);

        var viewer = learnerCourseAccess.resolveViewer(instructorUser, COURSE_ID);

        assertThat(viewer.progression().accessibleLessonIds()).containsExactly(1L);
        // They are previewing, not taking the course — nothing about their progress is claimed.
        assertThat(viewer.progression().tracksProgress()).isFalse();
    }

    @Test
    void aMissingCourseIsReportedAsMissing() {
        given(courseRepository.findById(COURSE_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> learnerCourseAccess.resolveViewer(studentUser, COURSE_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- writing -------------------------------------------------------------

    @Test
    void requireEnrolledRefusesALearnerWithoutAnEnrolment() {
        givenCourse(CourseStatus.PUBLISHED);
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(enrollmentRepository.findByCourseIdAndStudentId(COURSE_ID, 20L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> learnerCourseAccess.requireEnrolled(studentUser, COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.notEnrolled");
    }

    @Test
    void requireEnrolledRefusesALearnerWhoseSubscriptionLapsed() {
        givenCourse(CourseStatus.PUBLISHED);
        givenEnrolled();
        givenEntitled(false);

        assertThatThrownBy(() -> learnerCourseAccess.requireEnrolled(studentUser, COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.accessExpired");
    }

    @Test
    void requireEnrolledAdmitsAnEntitledLearner() {
        Course course = givenCourse(CourseStatus.PUBLISHED);
        givenEnrolled();
        givenEntitled(true);
        given(courseProgressionService.progressionOf(any(), any())).willReturn(progressionFor(course));

        assertThat(learnerCourseAccess.requireEnrolled(studentUser, COURSE_ID).isEnrolled()).isTrue();
    }

    @Test
    void requireEnrolledRefusesAnInstructorEvenOnTheirOwnCourse() {
        assertThatThrownBy(() -> learnerCourseAccess.requireEnrolled(instructorUser, COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.onlyStudent");
    }

    @Test
    void requireEnrolledRefusesADraftCourse() {
        givenCourse(CourseStatus.DRAFT);

        assertThatThrownBy(() -> learnerCourseAccess.requireEnrolled(studentUser, COURSE_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("error.course.notFound");
    }

    // --- fixtures ------------------------------------------------------------

    private Course givenCourse(CourseStatus status) {
        Course course = Course.builder()
                .id(COURSE_ID)
                .title("Course")
                .status(status)
                .structure(CourseStructure.FLAT)
                .instructor(Instructor.builder().id(10L).user(instructorUser).build())
                .build();
        given(courseRepository.findById(COURSE_ID)).willReturn(Optional.of(course));
        lenient().when(courseAggregateLoader.load(course)).thenReturn(aggregateOf(course));
        return course;
    }

    private void givenEnrolled() {
        given(studentRepository.findByUserId(2L)).willReturn(Optional.of(student));
        given(enrollmentRepository.findByCourseIdAndStudentId(COURSE_ID, 20L))
                .willReturn(Optional.of(Enrollment.builder().id(40L).student(student).build()));
    }

    private CourseAggregate aggregateOf(Course course) {
        Lesson lesson = Lesson.builder().id(1L).title("Lesson").course(course).orderIndex(0).build();
        return new CourseAggregate(course, List.of(), List.of(lesson), Map.of(), Map.of(), null, List.of());
    }

    private void givenEntitled(boolean entitled) {
        given(entitlementPolicy.isEntitled(COURSE_ID, 20L)).willReturn(entitled);
    }

    private CourseProgression progressionFor(Course course) {
        return new CourseProgressionCalculator().compute(aggregateOf(course), Set.of(), Map.of());
    }

    /** A learner who finished the course's only lesson, so the lapse has something to preserve. */
    private CourseProgression completedProgressionFor(Course course) {
        return new CourseProgressionCalculator().compute(aggregateOf(course), Set.of(1L), Map.of());
    }
}
