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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonOrder;
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
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

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
    @DisplayName("a focused command writes only what it touched")
    class NarrowWrites {

        /**
         * The interleaving the threaded tests can only stumble on, forced to happen every time.
         *
         * <p>Hibernate's default UPDATE lists every column of the entity, so a transaction that
         * changed one field writes back every other field as <em>it</em> read them. That turns any
         * focused command into a whole-row overwrite of a snapshot that may already be out of date —
         * a reorder that started before a rename committed would undo the rename on its way out,
         * having never touched the title. Which is exactly what the focused commands exist to
         * prevent, so they do not work at all without {@code @DynamicUpdate}.
         *
         * <p>Staged rather than raced: the course is read into one transaction, renamed and
         * committed by another, and only then does the first transaction dirty the row it is
         * holding and commit. Without the annotation the rename is gone; with it, it stands.
         */
        @Test
        @DisplayName("a course update does not write back columns the transaction never changed")
        void anUpdateDoesNotClobberColumnsItNeverTouched() {
            var course = publishedCourse();
            var externalWriter = new TransactionTemplate(transactionManager);
            externalWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            transactionTemplate.executeWithoutResult(status -> {
                // 1. This transaction reads the course, title included.
                var held = courseRepository.findById(course.getId()).orElseThrow();
                assertThat(held.getTitle()).isEqualTo("Guarded");

                // 2. Somebody else renames it and commits, on a connection of their own.
                externalWriter.executeWithoutResult(inner ->
                        jdbc.update("UPDATE courses SET title = ? WHERE id = ?",
                                "Renamed by another request", course.getId()));

                // 3. This transaction now changes something else entirely and commits.
                held.markContentChanged(java.time.LocalDateTime.now());
            });

            assertThat(reload(course.getId()).getTitle())
                    .as("the rename must survive a transaction that only moved the content version")
                    .isEqualTo("Renamed by another request");
        }

        @Test
        @DisplayName("a module update does not write back columns the transaction never changed")
        void aModuleUpdateDoesNotClobberColumnsItNeverTouched() {
            var course = publishedCourse();
            var moduleId = moduleIdsOf(course).get(0);
            var externalWriter = new TransactionTemplate(transactionManager);
            externalWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            transactionTemplate.executeWithoutResult(status -> {
                var held = courseModuleRepository.findById(moduleId).orElseThrow();
                assertThat(held.getTitle()).isEqualTo("One");

                externalWriter.executeWithoutResult(inner ->
                        jdbc.update("UPDATE course_modules SET title = ? WHERE id = ?",
                                "Renamed by another request", moduleId));

                // What a reorder does, and the only thing it should write.
                held.setOrderIndex(held.getOrderIndex() + 10);
            });

            assertThat(courseModuleRepository.findById(moduleId).orElseThrow().getTitle())
                    .as("a reorder must not carry a stale module title back into the database")
                    .isEqualTo("Renamed by another request");
        }

        @Test
        @DisplayName("a lesson update does not write back columns the transaction never changed")
        void aLessonUpdateDoesNotClobberColumnsItNeverTouched() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Nested", CourseStatus.PUBLISHED,
                            module("Only", lesson("L1"), lesson("L2"))));
            var lessonId = moduleLessonIdsOf(course, 0).get(0);
            var externalWriter = new TransactionTemplate(transactionManager);
            externalWriter.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

            transactionTemplate.executeWithoutResult(status -> {
                var held = lessonRepository.findById(lessonId).orElseThrow();
                assertThat(held.getTitle()).isEqualTo("L1");

                externalWriter.executeWithoutResult(inner ->
                        jdbc.update("UPDATE lessons SET title = ? WHERE id = ?",
                                "Renamed by another request", lessonId));

                held.setOrderIndex(held.getOrderIndex() + 10);
            });

            assertThat(lessonRepository.findById(lessonId).orElseThrow().getTitle())
                    .as("a nested lesson reorder must not carry a stale lesson title back")
                    .isEqualTo("Renamed by another request");
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
        @DisplayName("two nested lesson reorders arriving together leave one whole order")
        void concurrentLessonReordersCannotCorruptTheOrder() throws Exception {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Nested", CourseStatus.PUBLISHED,
                            module("Only", lesson("L1"), lesson("L2"), lesson("L3"))));
            var moduleId = moduleIdsOf(course).get(0);
            var ids = moduleLessonIdsOf(course, 0);
            List<Long> reversed = List.of(ids.get(2), ids.get(1), ids.get(0));
            List<Long> rotated = List.of(ids.get(1), ids.get(2), ids.get(0));

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> {
                    start.await();
                    courseService.reorderModuleLessons(instructorUser, course.getId(), moduleId,
                            lessonOrder(reversed));
                    return null;
                });
                var second = pool.submit(() -> {
                    start.await();
                    courseService.reorderModuleLessons(instructorUser, course.getId(), moduleId,
                            lessonOrder(rotated));
                    return null;
                });
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            var finalIds = lessonRepository.findModuleLessons(course.getId(), moduleId)
                    .stream().map(l -> l.getId()).toList();

            assertThat(persistedModuleLessonPositions(course.getId(), moduleId))
                    .as("whichever reorder won, the positions must still be a clean 0..N-1 run")
                    .containsExactly(0, 1, 2);
            assertThat(finalIds)
                    .as("the stored order must be one of the two that were asked for, not a blend")
                    .isIn(reversed, rotated);
        }

        @Test
        @DisplayName("reorders of two different modules do not block or corrupt each other")
        void concurrentReordersOfDifferentModulesAreIndependent() throws Exception {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Two scopes", CourseStatus.PUBLISHED,
                            module("First", lesson("A1"), lesson("A2")),
                            module("Second", lesson("B1"), lesson("B2"))));
            var firstModuleId = moduleIdsOf(course).get(0);
            var secondModuleId = moduleIdsOf(course).get(1);
            var firstIds = moduleLessonIdsOf(course, 0);
            var secondIds = moduleLessonIdsOf(course, 1);

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> {
                    start.await();
                    courseService.reorderModuleLessons(instructorUser, course.getId(), firstModuleId,
                            lessonOrder(List.of(firstIds.get(1), firstIds.get(0))));
                    return null;
                });
                var second = pool.submit(() -> {
                    start.await();
                    courseService.reorderModuleLessons(instructorUser, course.getId(), secondModuleId,
                            lessonOrder(List.of(secondIds.get(1), secondIds.get(0))));
                    return null;
                });
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            // Separate scopes, so both drags land in full — neither overwrote the other.
            assertThat(persistedModuleLessonTitles(course.getId(), firstModuleId))
                    .containsExactly("A2", "A1");
            assertThat(persistedModuleLessonTitles(course.getId(), secondModuleId))
                    .containsExactly("B2", "B1");
        }

        @Test
        @DisplayName("a stale aggregate edit and a lesson reorder arriving together both survive")
        void aConcurrentEditAndLessonReorderDoNotEraseEachOther() throws Exception {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Nested", CourseStatus.PUBLISHED,
                            module("Only", lesson("L1"), lesson("L2"), lesson("L3"))));
            var moduleId = moduleIdsOf(course).get(0);
            var ids = moduleLessonIdsOf(course, 0);
            var staleCopy = echoOf(course);

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var edit = pool.submit(() -> {
                    start.await();
                    staleCopy.setTitle("Renamed concurrently");
                    courseService.updateCourse(instructorUser, course.getId(), staleCopy);
                    return null;
                });
                var reorder = pool.submit(() -> {
                    start.await();
                    courseService.reorderModuleLessons(instructorUser, course.getId(), moduleId,
                            lessonOrder(List.of(ids.get(2), ids.get(0), ids.get(1))));
                    return null;
                });
                start.countDown();
                edit.get(30, TimeUnit.SECONDS);
                reorder.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            // Whichever order the two landed in, the aggregate save carries no opinion about
            // lesson order any more — so the reorder is the only thing that decided it.
            assertThat(reload(course.getId()).getTitle()).isEqualTo("Renamed concurrently");
            assertThat(persistedModuleLessonTitles(course.getId(), moduleId))
                    .containsExactly("L3", "L1", "L2");
            assertThat(persistedModuleLessonPositions(course.getId(), moduleId))
                    .containsExactly(0, 1, 2);
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
