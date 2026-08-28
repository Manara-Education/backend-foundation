package com.manara.backend.course.integration;

import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.lesson.service.LessonService;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unpublishing a course, and what it does to the learners already studying it.
 *
 * <h2>The failure this closes</h2>
 * {@code CourseService#unpublish} has always documented itself as "Withdraws a course from the
 * catalogue. Content, learners and their history are untouched." The implementation did not agree:
 * every learner-facing lookup gated on {@code status == PUBLISHED} alone, so an enrolled learner
 * asking for the course they were half way through was answered {@code 404}. Their enrolment, their
 * entitlement and their progress all survived — they simply could not reach any of it until the
 * instructor put the course back.
 *
 * <p>Publication and entitlement are different questions. Publication decides who may
 * <em>discover and acquire</em> the course; entitlement decides who may <em>open</em> one they
 * already hold. This file asserts both halves: the learner keeps their course, and nobody else can
 * find it.
 */
class UnpublishedCourseAccessTest extends AbstractCourseAuthoringTest {

    @Autowired LessonService lessonService;

    private User learner;
    private User stranger;

    @BeforeEach
    void createLearners() {
        learner = newStudentUser();
        stranger = newStudentUser();
    }

    private InstructorCourseResponse enrolledModularCourse() {
        var course = courseService.createCourse(instructorUser,
                modularCourse("Withdrawn", CourseStatus.PUBLISHED,
                        module("One", lesson("L1"), lesson("L2"))));
        enroll(learner, course.getId());
        return course;
    }

    private void unpublish(Long courseId) {
        courseService.unpublish(instructorUser, courseId);
        assertThat(reload(courseId).getStatus()).isEqualTo(CourseStatus.DRAFT);
    }

    private Long firstLessonOf(Long courseId) {
        return lessonRepository.findCourseLessonsInReadingOrder(courseId).getFirst().getId();
    }

    @Nested
    @DisplayName("the learner who already holds it")
    class ExistingLearner {

        @Test
        @DisplayName("still has it in their library, and can still open it")
        void keepsTheirCourse() {
            var course = enrolledModularCourse();
            unpublish(course.getId());

            assertThat(cardFor(learner, course.getId()).getTitle()).isEqualTo("Withdrawn");

            var details = courseService.getCourseDetails(learner, course.getId(), CourseViewMode.ENROLLED);
            assertThat(details.getCourse().getTitle()).isEqualTo("Withdrawn");
            assertThat(details.getModules()).extracting(m -> m.getTitle()).containsExactly("One");
        }

        @Test
        @DisplayName("can still open a lesson, and their progress is where they left it")
        void keepsTheirLessonsAndProgress() {
            var course = enrolledModularCourse();
            Long lessonId = firstLessonOf(course.getId());
            lessonService.markLessonCompleted(learner, course.getId(), lessonId);

            unpublish(course.getId());

            assertThat(lessonService.getLesson(learner, course.getId(), lessonId).getLesson().getTitle())
                    .isEqualTo("L1");
            assertThat(lessonService.getCourseLessons(learner, course.getId()))
                    .extracting(l -> l.getTitle()).containsExactly("L1", "L2");
            assertThat(completedLessonRepository
                    .findCompletedLessonIdsByStudentIdAndCourseId(
                            studentProfileOf(learner).getId(), course.getId()))
                    .containsExactly(lessonId);
        }

        @Test
        @DisplayName("keeps their entitlement, and it is still active")
        void keepsTheirEntitlement() {
            var course = enrolledModularCourse();
            unpublish(course.getId());

            var entitlement = courseEntitlementRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();
            assertThat(entitlement.getSource()).isEqualTo(EntitlementSource.FREE);
            assertThat(entitlement.isActiveAt(LocalDateTime.now())).isTrue();
        }

        @Test
        @DisplayName("a FLAT course behaves identically")
        void flatCourseIsTheSame() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Flat withdrawn", CourseStatus.PUBLISHED, lesson("F1"), lesson("F2")));
            enroll(learner, course.getId());
            unpublish(course.getId());

            assertThat(courseService.getCourseDetails(learner, course.getId(), CourseViewMode.ENROLLED)
                    .getLessons()).extracting(l -> l.getTitle()).containsExactly("F1", "F2");
        }

        /**
         * A learner who paid keeps what they paid for, whatever the catalogue says.
         *
         * <p>Publication is about acquisition. A course going off sale cannot be a way of taking
         * back access somebody has already bought.
         */
        @Test
        @DisplayName("a purchaser keeps a course that is taken off the catalogue")
        void aPurchaserKeepsTheirCourse() {
            var request = flatCourse("Bought", CourseStatus.PUBLISHED, lesson("L1"));
            request.setAccessType(CourseAccessType.PURCHASE);
            request.setPurchasePrice(new BigDecimal("300.00"));
            var course = courseService.createCourse(instructorUser, request);
            enroll(learner, course.getId());

            unpublish(course.getId());

            assertThat(courseService.getCourseDetails(learner, course.getId(), CourseViewMode.ENROLLED)
                    .getCourse().getTitle()).isEqualTo("Bought");
        }

        @Test
        @DisplayName("a subscriber keeps a course that is taken off the catalogue")
        void aSubscriberKeepsTheirCourse() {
            var request = flatCourse("Subscribed", CourseStatus.PUBLISHED, lesson("L1"));
            request.setAccessType(CourseAccessType.SUBSCRIPTION);
            request.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00")));
            var course = courseService.createCourse(instructorUser, request);
            enroll(learner, course.getId());

            unpublish(course.getId());

            assertThat(courseService.getCourseDetails(learner, course.getId(), CourseViewMode.ENROLLED)
                    .getCourse().getTitle()).isEqualTo("Subscribed");
        }
    }

    @Nested
    @DisplayName("everybody else")
    class NotALearner {

        @Test
        @DisplayName("cannot find it in the catalogue")
        void itLeavesTheCatalogue() {
            var course = enrolledModularCourse();
            assertThat(courseService.getDiscoverableCourses()).extracting(c -> c.getId())
                    .contains(course.getId());

            unpublish(course.getId());

            assertThat(courseService.getDiscoverableCourses()).extracting(c -> c.getId())
                    .doesNotContain(course.getId());
        }

        @Test
        @DisplayName("cannot open it by id, in either view mode")
        void aStrangerIsToldItDoesNotExist() {
            var course = enrolledModularCourse();
            unpublish(course.getId());

            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.ENROLLED))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("cannot reach its lessons either")
        void aStrangerCannotReadItsLessons() {
            var course = enrolledModularCourse();
            Long lessonId = firstLessonOf(course.getId());
            unpublish(course.getId());

            assertThatThrownBy(() -> lessonService.getCourseLessons(stranger, course.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> lessonService.getLesson(stranger, course.getId(), lessonId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("a signed-out visitor is told the same thing")
        void anAnonymousVisitorIsToldItDoesNotExist() {
            var course = enrolledModularCourse();
            unpublish(course.getId());

            assertThatThrownBy(() ->
                    courseService.getCourseDetails(null, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("putting it back")
    class Republishing {

        @Test
        @DisplayName("discovery returns and nothing about the learner changed")
        void republishingRestoresDiscoveryAndChangesNothingElse() {
            var course = enrolledModularCourse();
            Long lessonId = firstLessonOf(course.getId());
            lessonService.markLessonCompleted(learner, course.getId(), lessonId);
            var enrolmentBefore = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();
            var progressBefore = enrolmentBefore.getProgress();

            unpublish(course.getId());
            courseService.publish(instructorUser, course.getId());

            assertThat(courseService.getDiscoverableCourses()).extracting(c -> c.getId())
                    .contains(course.getId());
            assertThat(courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER)
                    .getCourse().getTitle()).isEqualTo("Withdrawn");

            var enrolmentAfter = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();
            assertThat(enrolmentAfter.getId()).isEqualTo(enrolmentBefore.getId());
            assertThat(enrolmentAfter.getProgress()).isEqualTo(progressBefore);
            assertThat(completedLessonRepository
                    .findCompletedLessonIdsByStudentIdAndCourseId(
                            studentProfileOf(learner).getId(), course.getId()))
                    .containsExactly(lessonId);
        }
    }
}
