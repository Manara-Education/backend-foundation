package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.service.LessonService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The standalone lesson endpoints, and where a lesson lands.
 *
 * <h2>The failure this closes</h2>
 * {@code POST /instructor/courses/{id}/lessons} wrote the client's {@code orderIndex} straight into
 * a {@code UNIQUE (course_id, module_id, order_index)} constraint. The field was also mandatory, so
 * a client had to nominate a position — and the obvious one, {@code 0}, was answered
 * {@code 409 "The request conflicts with data that already exists"}. There was no way to say "put
 * it at the end", and two clients adding at once could only be settled by the database refusing one.
 *
 * <p>The client was being asked to compute a value only the server knows, against a rule only the
 * server enforces. It no longer is.
 *
 * <p>Every case ends with the same check: the scope reads {@code 0..n-1}, with no gaps and no
 * duplicates, and the scopes the operation had no business touching are exactly as they were.
 */
class StandaloneLessonOrderingTest extends AbstractCourseAuthoringTest {

    @Autowired LessonService lessonService;

    private InstructorCourseResponse flatThree() {
        return courseService.createCourse(instructorUser,
                flatCourse("Flat", CourseStatus.PUBLISHED, lesson("A"), lesson("B"), lesson("C")));
    }

    private InstructorCourseResponse twoModules() {
        return courseService.createCourse(instructorUser,
                modularCourse("Modular", CourseStatus.PUBLISHED,
                        module("One", lesson("A"), lesson("B"), lesson("C")),
                        module("Two", lesson("X"), lesson("Y"))));
    }

    private LessonRequest newLesson(String title, Long moduleId, Integer orderIndex) {
        return LessonRequest.builder()
                .title(title)
                .description(title + " description")
                .videoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .moduleId(moduleId)
                .orderIndex(orderIndex)
                .build();
    }

    private void assertContiguousRoot(Long courseId, String... expectedTitles) {
        assertThat(persistedRootLessonTitles(courseId)).containsExactly(expectedTitles);
        assertThat(persistedRootLessonPositions(courseId))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, expectedTitles.length)
                        .boxed().toList());
    }

    private void assertContiguousModule(Long courseId, Long moduleId, String... expectedTitles) {
        assertThat(persistedModuleLessonTitles(courseId, moduleId)).containsExactly(expectedTitles);
        assertThat(persistedModuleLessonPositions(courseId, moduleId))
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, expectedTitles.length)
                        .boxed().toList());
    }

    @Nested
    @DisplayName("adding")
    class Adding {

        @Test
        @DisplayName("a FLAT lesson with no position asked for goes to the end")
        void appendsToAFlatCourse() {
            var course = flatThree();

            lessonService.addLesson(instructorUser, course.getId(), newLesson("D", null, null));

            assertContiguousRoot(course.getId(), "A", "B", "C", "D");
        }

        @Test
        @DisplayName("a nested lesson with no position asked for goes to the end of its module")
        void appendsToAModule() {
            var course = twoModules();
            var first = moduleIdsOf(course).getFirst();
            var second = moduleIdsOf(course).get(1);

            lessonService.addLesson(instructorUser, course.getId(), newLesson("D", first, null));

            assertContiguousModule(course.getId(), first, "A", "B", "C", "D");
            assertContiguousModule(course.getId(), second, "X", "Y");
        }

        /** The exact request the audit reproduced: position 0, on a scope that already has one. */
        @Test
        @DisplayName("position 0 inserts at the top and shifts the rest along")
        void insertsAtTheBeginning() {
            var course = flatThree();

            lessonService.addLesson(instructorUser, course.getId(), newLesson("D", null, 0));

            assertContiguousRoot(course.getId(), "D", "A", "B", "C");
        }

        @Test
        @DisplayName("a position in the middle inserts there")
        void insertsInTheMiddle() {
            var course = flatThree();

            lessonService.addLesson(instructorUser, course.getId(), newLesson("D", null, 1));

            assertContiguousRoot(course.getId(), "A", "D", "B", "C");
        }

        @Test
        @DisplayName("the position one past the last is the end, not an error")
        void insertsAtTheEnd() {
            var course = flatThree();

            lessonService.addLesson(instructorUser, course.getId(), newLesson("D", null, 3));

            assertContiguousRoot(course.getId(), "A", "B", "C", "D");
        }

        @Test
        @DisplayName("a position beyond the scope is refused by name, not by constraint")
        void refusesAPositionOutsideTheScope() {
            var course = flatThree();

            assertThatThrownBy(() -> lessonService.addLesson(instructorUser, course.getId(),
                    newLesson("D", null, 9)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_LESSON_POSITION));

            assertContiguousRoot(course.getId(), "A", "B", "C");
        }

        @Test
        @DisplayName("a negative position is refused the same way")
        void refusesANegativePosition() {
            var course = flatThree();

            assertThatThrownBy(() -> lessonService.addLesson(instructorUser, course.getId(),
                    newLesson("D", null, -1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> assertThat(((BusinessException) thrown).getErrorCode())
                            .isEqualTo(ErrorCode.INVALID_LESSON_POSITION));
        }

        /**
         * Two clients adding to one scope at the same moment.
         *
         * <p>Both used to compute the same "next" position and one lost at the unique constraint.
         * The course row is locked before either reads the scope, so they are placed one after the
         * other — and the result is still a contiguous run, whichever went first.
         */
        @Test
        @DisplayName("two lessons added at once are placed one after the other")
        void concurrentAddsDoNotCollide() throws Exception {
            var course = flatThree();

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> {
                    start.await();
                    return lessonService.addLesson(instructorUser, course.getId(),
                            newLesson("D", null, null));
                });
                var second = pool.submit(() -> {
                    start.await();
                    return lessonService.addLesson(instructorUser, course.getId(),
                            newLesson("E", null, null));
                });
                start.countDown();
                first.get(30, TimeUnit.SECONDS);
                second.get(30, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactlyInAnyOrder("A", "B", "C", "D", "E");
            assertThat(persistedRootLessonPositions(course.getId())).containsExactly(0, 1, 2, 3, 4);
        }
    }

    @Nested
    @DisplayName("moving and removing")
    class MovingAndRemoving {

        @Test
        @DisplayName("a lesson moved within its scope takes the position and the rest close up")
        void movesWithinScope() {
            var course = flatThree();
            var last = lessonRepository.findRootLessons(course.getId()).get(2);

            lessonService.updateLesson(instructorUser, course.getId(), last.getId(),
                    newLesson("C", null, 0));

            assertContiguousRoot(course.getId(), "C", "A", "B");
        }

        @Test
        @DisplayName("a lesson moved to another module appends there and closes the gap behind it")
        void movesAcrossModules() {
            var course = twoModules();
            var first = moduleIdsOf(course).getFirst();
            var second = moduleIdsOf(course).get(1);
            var moving = lessonRepository.findModuleLessons(course.getId(), first).getFirst();

            lessonService.updateLesson(instructorUser, course.getId(), moving.getId(),
                    newLesson("A", second, null));

            assertContiguousModule(course.getId(), first, "B", "C");
            assertContiguousModule(course.getId(), second, "X", "Y", "A");
        }

        @Test
        @DisplayName("a lesson moved to another module at a position lands there")
        void movesAcrossModulesToAPosition() {
            var course = twoModules();
            var first = moduleIdsOf(course).getFirst();
            var second = moduleIdsOf(course).get(1);
            var moving = lessonRepository.findModuleLessons(course.getId(), first).get(1);

            lessonService.updateLesson(instructorUser, course.getId(), moving.getId(),
                    newLesson("B", second, 1));

            assertContiguousModule(course.getId(), first, "A", "C");
            assertContiguousModule(course.getId(), second, "X", "B", "Y");
        }

        @Test
        @DisplayName("an edit that says nothing about position leaves the lesson where it is")
        void anEditWithoutAPositionDoesNotMoveIt() {
            var course = flatThree();
            var middle = lessonRepository.findRootLessons(course.getId()).get(1);

            lessonService.updateLesson(instructorUser, course.getId(), middle.getId(),
                    newLesson("B, renamed", null, null));

            assertContiguousRoot(course.getId(), "A", "B, renamed", "C");
        }

        @Test
        @DisplayName("deleting from the middle closes the gap")
        void deletingCompactsTheScope() {
            var course = flatThree();
            var middle = lessonRepository.findRootLessons(course.getId()).get(1);

            lessonService.deleteLesson(instructorUser, course.getId(), middle.getId());

            assertContiguousRoot(course.getId(), "A", "C");
        }

        @Test
        @DisplayName("deleting from one module does not disturb another")
        void deletingLeavesOtherScopesAlone() {
            var course = twoModules();
            var first = moduleIdsOf(course).getFirst();
            var second = moduleIdsOf(course).get(1);
            var middle = lessonRepository.findModuleLessons(course.getId(), first).get(1);

            lessonService.deleteLesson(instructorUser, course.getId(), middle.getId());

            assertContiguousModule(course.getId(), first, "A", "C");
            assertContiguousModule(course.getId(), second, "X", "Y");
        }
    }
}
