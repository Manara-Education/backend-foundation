package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.profile.model.Student;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Who may change a course, what a failed change leaves behind, and what a successful one must never
 * take with it.
 */
class CourseAuthoringSafetyTest extends AbstractCourseAuthoringTest {

    @Autowired TransactionTemplate transactionTemplate;

    private InstructorCourseResponse publishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Guarded", CourseStatus.PUBLISHED,
                        module("One", lesson("L1")), module("Two", lesson("L2")), module("Three", lesson("L3"))));
    }

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("the owner may edit, reorder and publish")
        void theOwnerMay() {
            var course = publishedCourse();
            var ids = moduleIdsOf(course);
            var request = echoOf(course);
            request.setTitle("Owner's edit");

            assertThat(courseService.updateCourse(instructorUser, course.getId(), request).getTitle())
                    .isEqualTo("Owner's edit");
            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(2), ids.get(1), ids.get(0))));
            courseService.publish(instructorUser, course.getId());

            assertThat(persistedModuleTitles(course.getId())).containsExactly("Three", "Two", "One");
            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        }

        @Test
        @DisplayName("another instructor may not, however valid their own credentials")
        void anotherInstructorMayNot() {
            var course = publishedCourse();
            User intruder = newInstructorUser();
            var request = echoOf(course);
            request.setTitle("Not yours");
            var ids = moduleIdsOf(course);

            assertThatThrownBy(() -> courseService.updateCourse(intruder, course.getId(), request))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.notOwner");
            assertThatThrownBy(() -> courseService.reorderModules(intruder, course.getId(),
                    order(List.of(ids.get(2), ids.get(1), ids.get(0)))))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.notOwner");
            assertThatThrownBy(() -> courseService.publish(intruder, course.getId()))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.notOwner");
            assertThatThrownBy(() -> courseService.unpublish(intruder, course.getId()))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.notOwner");
            assertThatThrownBy(() -> courseService.getCourseForEditing(intruder, course.getId()))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.notOwner");

            var untouched = reload(course.getId());
            assertThat(untouched.getTitle()).isEqualTo("Guarded");
            assertThat(persistedModuleTitles(course.getId())).containsExactly("One", "Two", "Three");
        }

        @Test
        @DisplayName("a student may not, on any of the authoring operations")
        void aStudentMayNot() {
            var course = publishedCourse();
            User student = newStudentUser();
            var request = echoOf(course);
            var ids = moduleIdsOf(course);

            assertThatThrownBy(() -> courseService.updateCourse(student, course.getId(), request))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.onlyInstructor");
            assertThatThrownBy(() -> courseService.reorderModules(student, course.getId(), order(ids)))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.onlyInstructor");
            assertThatThrownBy(() -> courseService.publish(student, course.getId()))
                    .isInstanceOf(BusinessException.class).hasMessage("error.course.onlyInstructor");
        }

        @Test
        @DisplayName("a course id that does not exist is a 404, not a leak")
        void anUnknownCourse() {
            assertThatThrownBy(() -> courseService.publish(instructorUser, 9_999_999L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("existing learners")
    class HistoricalData {

        @Test
        @DisplayName("keep their enrolment and their progress when the instructor edits the course")
        void enrolmentAndProgressSurviveAnEdit() {
            var course = publishedCourse();
            User studentUser = newStudentUser();
            Student student = studentProfileOf(studentUser);

            var enrolment = enrollmentRepository.save(com.manara.backend.course.model.Enrollment.builder()
                    .course(reload(course.getId())).student(student).build());
            var firstLesson = lessonRepository.findByCourseIdOrderByOrderIndexAsc(course.getId()).get(0);
            completedLessonRepository.save(CompletedLesson.builder()
                    .student(student).lesson(firstLesson).build());

            var request = echoOf(course);
            request.setTitle("Edited under a learner's feet");
            courseService.updateCourse(instructorUser, course.getId(), request);
            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(moduleIdsOf(course).get(2), moduleIdsOf(course).get(0), moduleIdsOf(course).get(1))));

            assertThat(enrollmentRepository.findById(enrolment.getId())).isPresent();
            assertThat(completedLessonRepository
                    .countByStudentIdAndLesson_Course_Id(student.getId(), course.getId())).isEqualTo(1);
        }

        @Test
        @DisplayName("keep the progress of the lessons that stayed when one module is removed")
        void progressOfSurvivingLessonsIsKept() {
            var course = publishedCourse();
            User studentUser = newStudentUser();
            Student student = studentProfileOf(studentUser);

            var lessons = lessonRepository.findByCourseIdOrderByOrderIndexAsc(course.getId());
            lessons.forEach(l -> completedLessonRepository.save(
                    CompletedLesson.builder().student(student).lesson(l).build()));
            assertThat(completedLessonRepository
                    .countByStudentIdAndLesson_Course_Id(student.getId(), course.getId())).isEqualTo(3);

            var withoutThird = echoOf(course);
            withoutThird.setModules(List.of(withoutThird.getModules().get(0), withoutThird.getModules().get(1)));
            courseService.updateCourse(instructorUser, course.getId(), withoutThird);

            assertThat(completedLessonRepository
                    .countByStudentIdAndLesson_Course_Id(student.getId(), course.getId()))
                    .as("only the removed lesson's row goes; the other two are the learner's history")
                    .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("transactions")
    class Transactions {

        @Test
        @DisplayName("a reorder that is rolled back leaves no position behind")
        void aRolledBackReorderPersistsNothing() {
            var course = publishedCourse();
            var ids = moduleIdsOf(course);

            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                courseService.reorderModules(instructorUser, course.getId(),
                        order(List.of(ids.get(2), ids.get(1), ids.get(0))));
                throw new IllegalStateException("something later in the request failed");
            })).isInstanceOf(IllegalStateException.class);

            assertThat(persistedModuleTitles(course.getId()))
                    .as("a half-applied permutation is worse than no reorder at all")
                    .containsExactly("One", "Two", "Three");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("a rejected content save leaves the whole course as it was")
        void aRejectedSavePersistsNothing() {
            var course = publishedCourse();

            var invalid = echoOf(course);
            invalid.setTitle("Never stored");
            invalid.getModules().get(0).setTitle("Also never stored");
            // The last lesson of the payload carries a link Manara cannot play, so the whole
            // payload is refused — after the earlier modules would otherwise have been rewritten.
            invalid.getModules().get(2).getLessons().get(0).setVideoUrl("https://example.com/not-a-video");

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), invalid))
                    .isInstanceOf(BusinessException.class);

            var reloaded = reload(course.getId());
            assertThat(reloaded.getTitle()).isEqualTo("Guarded");
            assertThat(persistedModuleTitles(course.getId())).containsExactly("One", "Two", "Three");
        }
    }

    @Nested
    @DisplayName("concurrency")
    class Concurrency {

        @Test
        @DisplayName("two reorders arriving together leave one whole order, never an interleaving of both")
        void concurrentReordersCannotCorruptTheOrder() throws Exception {
            var course = publishedCourse();
            var ids = moduleIdsOf(course);
            List<Long> reversed = List.of(ids.get(2), ids.get(1), ids.get(0));
            List<Long> rotated = List.of(ids.get(1), ids.get(2), ids.get(0));

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> {
                    start.await();
                    courseService.reorderModules(instructorUser, course.getId(), order(reversed));
                    return null;
                });
                var second = pool.submit(() -> {
                    start.await();
                    courseService.reorderModules(instructorUser, course.getId(), order(rotated));
                    return null;
                });
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            var finalIds = courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId())
                    .stream().map(m -> m.getId()).toList();

            assertThat(persistedModulePositions(course.getId()))
                    .as("whichever reorder won, the positions must still be a clean 0..N-1 run")
                    .containsExactly(0, 1, 2);
            assertThat(finalIds)
                    .as("the stored order must be one of the two that were asked for, not a blend")
                    .isIn(reversed, rotated);
        }

        @Test
        @DisplayName("a metadata edit and a reorder arriving together both survive")
        void aConcurrentEditAndReorderDoNotEraseEachOther() throws Exception {
            var course = publishedCourse();
            var ids = moduleIdsOf(course);

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var edit = pool.submit(() -> {
                    start.await();
                    var request = echoOf(course);
                    request.setTitle("Renamed concurrently");
                    courseService.updateCourse(instructorUser, course.getId(), request);
                    return null;
                });
                var reorder = pool.submit(() -> {
                    start.await();
                    courseService.reorderModules(instructorUser, course.getId(),
                            order(List.of(ids.get(2), ids.get(0), ids.get(1))));
                    return null;
                });
                start.countDown();
                edit.get(30, TimeUnit.SECONDS);
                reorder.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Renamed concurrently");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }
    }

    @Nested
    @DisplayName("the database itself refuses a duplicate position")
    class DatabaseIntegrity {

        @Test
        void twoModulesCannotShareAPosition() {
            var course = publishedCourse();

            assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                var modules = new ArrayList<>(courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId()));
                modules.get(1).setOrderIndex(modules.get(0).getOrderIndex());
                courseModuleRepository.saveAll(modules);
                // Deferred to COMMIT, which is what lets a legitimate permutation pass through a
                // transient collision — so the violation surfaces here, at the end.
            })).hasMessageContaining("uk_course_modules_course_order");

            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }
    }
}
