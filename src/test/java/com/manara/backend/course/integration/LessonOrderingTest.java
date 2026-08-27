package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonOrder;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two lesson scopes a course can have, and the commands that order them.
 *
 * <p>A course orders three things — its modules, the root lessons of a flat course, and the lessons
 * inside each module — and until this release only the first of them could be persisted. Dragging a
 * lesson inside a module reached the module-order endpoint, which meant the lesson order was never
 * written and, when the module count happened to differ from the lesson count, the drag failed
 * outright. These are the tests for the two scopes that were missing, and for the boundary between
 * all three: reordering one must not touch the others.
 *
 * <p>Every assertion re-reads from PostgreSQL. "The response came back in the right order" is
 * exactly what a reorder that never reached the database also looks like.
 */
class LessonOrderingTest extends AbstractCourseAuthoringTest {

    private InstructorCourseResponse threeLessonFlatCourse() {
        return courseService.createCourse(instructorUser,
                flatCourse("Flat", CourseStatus.PUBLISHED,
                        lesson("Alpha"), lesson("Beta"), lesson("Gamma")));
    }

    private InstructorCourseResponse twoModuleCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Modular", CourseStatus.PUBLISHED,
                        module("First", lesson("A1"), lesson("A2"), lesson("A3")),
                        module("Second", lesson("B1"), lesson("B2"))));
    }

    // ── Root lessons ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("the root lesson order command")
    class RootLessons {

        @Test
        @DisplayName("puts the lessons in the requested order, and the database agrees")
        void reorderSurvivesADatabaseReload() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);

            courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(2), ids.get(0), ids.get(1))));

            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Gamma", "Alpha", "Beta");
            assertThat(persistedRootLessonPositions(course.getId())).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("leaves the course published and marks it updated")
        void reorderKeepsTheCoursePublishedAndSignalsTheUpdate() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);
            var before = reload(course.getId());

            var response = courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(1), ids.get(0), ids.get(2))));

            var after = reload(course.getId());
            assertThat(after.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(after.hasUpdatesSincePublish()).isTrue();
            assertThat(after.getContentUpdatedAt()).isAfter(before.getContentUpdatedAt());
            assertThat(after.getContentUpdatedAt()).isAfter(after.getLastPublishedAt());
            assertThat(response.getHasUpdatesSincePublish()).isTrue();
            assertThat(response.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        }

        @Test
        @DisplayName("the order it is already in writes nothing and raises no badge")
        void aNoOpReorderIsANoOp() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);
            courseService.publish(instructorUser, course.getId());
            var before = reload(course.getId());

            courseService.reorderLessons(instructorUser, course.getId(), lessonOrder(ids));

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isFalse();
            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Alpha", "Beta", "Gamma");
        }

        @Test
        @DisplayName("does not touch module order")
        void reorderingRootLessonsLeavesModulesAlone() {
            // A flat course has no modules, so the closest thing to a cross-scope accident here is
            // the reverse: the module command must not accept a lesson list.
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(), order(ids)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("moduleNotInCourse");
        }

        @Test
        @DisplayName("a modular course has no root lesson scope, so the command is refused")
        void rootReorderOnAModularCourseIsRejected() {
            var course = twoModuleCourse();
            var ids = moduleLessonIdsOf(course, 0);

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(ids)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonNotInScope");
        }
    }

    // ── Module lessons ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("the nested lesson order command")
    class ModuleLessons {

        @Test
        @DisplayName("orders one module's lessons and leaves the other module untouched")
        void reorderIsScopedToOneModule() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);
            var secondModuleId = moduleIdsOf(course).get(1);
            var ids = moduleLessonIdsOf(course, 0);

            courseService.reorderModuleLessons(instructorUser, course.getId(), firstModuleId,
                    lessonOrder(List.of(ids.get(2), ids.get(1), ids.get(0))));

            assertThat(persistedModuleLessonTitles(course.getId(), firstModuleId))
                    .containsExactly("A3", "A2", "A1");
            assertThat(persistedModuleLessonPositions(course.getId(), firstModuleId))
                    .containsExactly(0, 1, 2);
            assertThat(persistedModuleLessonTitles(course.getId(), secondModuleId))
                    .containsExactly("B1", "B2");
        }

        @Test
        @DisplayName("does not touch module order — the defect this whole scope existed to expose")
        void reorderingLessonsLeavesModuleOrderAlone() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);
            var ids = moduleLessonIdsOf(course, 0);

            courseService.reorderModuleLessons(instructorUser, course.getId(), firstModuleId,
                    lessonOrder(List.of(ids.get(1), ids.get(0), ids.get(2))));

            assertThat(persistedModuleTitles(course.getId())).containsExactly("First", "Second");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1);
        }

        @Test
        @DisplayName("keeps the course published and marks it updated")
        void reorderKeepsTheCoursePublishedAndSignalsTheUpdate() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);
            var ids = moduleLessonIdsOf(course, 0);
            var before = reload(course.getId());

            courseService.reorderModuleLessons(instructorUser, course.getId(), firstModuleId,
                    lessonOrder(List.of(ids.get(1), ids.get(0), ids.get(2))));

            var after = reload(course.getId());
            assertThat(after.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(after.hasUpdatesSincePublish()).isTrue();
            assertThat(after.getContentUpdatedAt()).isAfter(before.getContentUpdatedAt());
        }

        @Test
        @DisplayName("the order it is already in writes nothing and raises no badge")
        void aNoOpReorderIsANoOp() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);
            var ids = moduleLessonIdsOf(course, 0);
            courseService.publish(instructorUser, course.getId());
            var before = reload(course.getId());

            courseService.reorderModuleLessons(instructorUser, course.getId(), firstModuleId,
                    lessonOrder(ids));

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isFalse();
        }

        @Test
        @DisplayName("a lesson from a sibling module is not in this module's scope")
        void aLessonFromAnotherModuleIsRejected() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);
            var firstIds = moduleLessonIdsOf(course, 0);
            var foreign = moduleLessonIdsOf(course, 1).get(0);

            assertThatThrownBy(() -> courseService.reorderModuleLessons(instructorUser, course.getId(),
                    firstModuleId, lessonOrder(List.of(firstIds.get(0), firstIds.get(1), foreign))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonNotInScope");

            assertThat(persistedModuleLessonTitles(course.getId(), firstModuleId))
                    .containsExactly("A1", "A2", "A3");
        }

        @Test
        @DisplayName("a module belonging to another course is not found")
        void aModuleFromAnotherCourseIsRejected() {
            var course = twoModuleCourse();
            var otherCourse = twoModuleCourse();
            var foreignModuleId = moduleIdsOf(otherCourse).get(0);

            assertThatThrownBy(() -> courseService.reorderModuleLessons(instructorUser, course.getId(),
                    foreignModuleId, lessonOrder(moduleLessonIdsOf(otherCourse, 0))))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("moduleNotInCourse");
        }
    }

    // ── Validation, shared by both lesson scopes ─────────────────────────────

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        @DisplayName("a null list is refused")
        void nullListIsRejected() {
            var course = threeLessonFlatCourse();

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonOrderRequired");
        }

        @Test
        @DisplayName("a null id inside the list is refused")
        void nullIdIsRejected() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(Arrays.asList(ids.get(0), null, ids.get(2)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonOrderNullId");
        }

        @Test
        @DisplayName("a duplicated id is refused")
        void duplicateIdIsRejected() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(0), ids.get(0), ids.get(1)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonOrderDuplicate");
        }

        @Test
        @DisplayName("an id from another instructor's course is refused")
        void foreignIdIsRejected() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);
            var other = courseService.createCourse(newInstructorUser(),
                    flatCourse("Theirs", CourseStatus.PUBLISHED, lesson("Not yours")));

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(0), ids.get(1), lessonIdsOf(other).get(0)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonNotInScope");
        }

        @Test
        @DisplayName("a list that omits a sibling is refused rather than half-applied")
        void incompleteListIsRejected() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(1), ids.get(0)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonOrderIncomplete");

            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Alpha", "Beta", "Gamma");
        }

        @Test
        @DisplayName("an empty list against a populated scope is refused")
        void emptyListIsRejected() {
            var course = threeLessonFlatCourse();

            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("lessonOrderIncomplete");
        }

        @Test
        @DisplayName("a missing course is not found")
        void missingCourseIsRejected() {
            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, 9_999_999L,
                    lessonOrder(List.of(1L))))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("another instructor cannot reorder this course's lessons")
        void anotherInstructorIsRefused() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);
            var intruder = newInstructorUser();

            assertThatThrownBy(() -> courseService.reorderLessons(intruder, course.getId(),
                    lessonOrder(List.of(ids.get(2), ids.get(1), ids.get(0)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("notOwner");

            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Alpha", "Beta", "Gamma");
        }

        @Test
        @DisplayName("a rejected reorder leaves no partial order behind")
        void aRejectedReorderRollsBackCompletely() {
            var course = threeLessonFlatCourse();
            var ids = lessonIdsOf(course);
            var before = reload(course.getId());

            // The first two ids are a legitimate swap; the third is not in the scope. If the
            // command applied positions as it walked the list, the swap would already be written
            // by the time it reached the bad id.
            assertThatThrownBy(() -> courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(1), ids.get(0), 9_999_999L))))
                    .isInstanceOf(BusinessException.class);

            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Alpha", "Beta", "Gamma");
            assertThat(persistedRootLessonPositions(course.getId())).containsExactly(0, 1, 2);
            assertThat(reload(course.getId()).getContentUpdatedAt())
                    .isEqualTo(before.getContentUpdatedAt());
        }
    }

    // ── The aggregate save no longer owns order ──────────────────────────────

    @Nested
    @DisplayName("aggregate saves and lesson order")
    class AggregateSaves {

        @Test
        @DisplayName("a stale aggregate save cannot undo a root lesson reorder")
        void staleAggregateSaveCannotUndoRootLessonOrder() {
            var course = threeLessonFlatCourse();

            // Tab A loads the course and holds it.
            var staleCopyHeldByTabA = echoOf(course);

            // Tab B reorders.
            var ids = lessonIdsOf(course);
            courseService.reorderLessons(instructorUser, course.getId(),
                    lessonOrder(List.of(ids.get(2), ids.get(0), ids.get(1))));

            // Tab A saves an unrelated edit, carrying its pre-drag lesson array.
            staleCopyHeldByTabA.setTitle("Renamed from a stale tab");
            courseService.updateCourse(instructorUser, course.getId(), staleCopyHeldByTabA);

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Renamed from a stale tab");
            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Gamma", "Alpha", "Beta");
        }

        @Test
        @DisplayName("a stale aggregate save cannot undo a nested lesson reorder")
        void staleAggregateSaveCannotUndoModuleLessonOrder() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);

            var staleCopyHeldByTabA = echoOf(course);

            var ids = moduleLessonIdsOf(course, 0);
            courseService.reorderModuleLessons(instructorUser, course.getId(), firstModuleId,
                    lessonOrder(List.of(ids.get(2), ids.get(1), ids.get(0))));

            staleCopyHeldByTabA.setDescription("A different description, long enough to be real");
            courseService.updateCourse(instructorUser, course.getId(), staleCopyHeldByTabA);

            assertThat(persistedModuleLessonTitles(course.getId(), firstModuleId))
                    .containsExactly("A3", "A2", "A1");
        }

        @Test
        @DisplayName("a lesson added mid-list lands where the payload put it")
        void newLessonIsPlacedRelativeToItsPayloadNeighbours() {
            var course = threeLessonFlatCourse();

            var withInsert = echoOf(course);
            var lessons = new ArrayList<>(withInsert.getLessons());
            lessons.add(1, lesson("Inserted"));
            withInsert.setLessons(lessons);
            courseService.updateCourse(instructorUser, course.getId(), withInsert);

            assertThat(persistedRootLessonTitles(course.getId()))
                    .containsExactly("Alpha", "Inserted", "Beta", "Gamma");
            assertThat(persistedRootLessonPositions(course.getId())).containsExactly(0, 1, 2, 3);
        }

        @Test
        @DisplayName("deleting a lesson from the middle closes the gap it left")
        void deletingALessonNormalisesTheRest() {
            var course = threeLessonFlatCourse();

            var withoutMiddle = echoOf(course);
            withoutMiddle.setLessons(List.of(withoutMiddle.getLessons().get(0),
                    withoutMiddle.getLessons().get(2)));
            courseService.updateCourse(instructorUser, course.getId(), withoutMiddle);

            assertThat(persistedRootLessonTitles(course.getId())).containsExactly("Alpha", "Gamma");
            assertThat(persistedRootLessonPositions(course.getId())).containsExactly(0, 1);
        }

        @Test
        @DisplayName("a lesson moved between modules is placed in its new module, not left at its old position")
        void aReparentedLessonIsPlacedInItsNewScope() {
            var course = twoModuleCourse();
            var firstModuleId = moduleIdsOf(course).get(0);
            var secondModuleId = moduleIdsOf(course).get(1);

            // A3 (position 2 under "First") moves to the front of "Second".
            var moved = echoOf(course);
            var firstModule = moved.getModules().get(0);
            var secondModule = moved.getModules().get(1);
            var movingLesson = firstModule.getLessons().get(2);
            firstModule.setLessons(List.of(firstModule.getLessons().get(0), firstModule.getLessons().get(1)));
            var secondLessons = new ArrayList<>(secondModule.getLessons());
            secondLessons.add(0, movingLesson);
            secondModule.setLessons(secondLessons);
            courseService.updateCourse(instructorUser, course.getId(), moved);

            assertThat(persistedModuleLessonTitles(course.getId(), firstModuleId))
                    .containsExactly("A1", "A2");
            assertThat(persistedModuleLessonPositions(course.getId(), firstModuleId))
                    .containsExactly(0, 1);
            assertThat(persistedModuleLessonTitles(course.getId(), secondModuleId))
                    .containsExactly("A3", "B1", "B2");
            assertThat(persistedModuleLessonPositions(course.getId(), secondModuleId))
                    .containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("re-saving a course unchanged neither reorders nor raises the badge")
        void anUnchangedSaveIsANoOpAcrossEveryScope() {
            var course = twoModuleCourse();
            courseService.publish(instructorUser, course.getId());
            var before = reload(course.getId());

            courseService.updateCourse(instructorUser, course.getId(), echoOf(course));

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isFalse();
            assertThat(persistedModuleTitles(course.getId())).containsExactly("First", "Second");
            assertThat(persistedModuleLessonTitles(course.getId(), moduleIdsOf(course).get(0)))
                    .containsExactly("A1", "A2", "A3");
        }
    }
}
