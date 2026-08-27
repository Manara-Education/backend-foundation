package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module order is explicit, persisted, contiguous, and survives a reload.
 *
 * <p>Every assertion here re-reads the modules from PostgreSQL rather than inspecting the response,
 * because "the response came back in the right order" is exactly what a reorder that never reached
 * the database also looks like.
 */
class ModuleOrderingTest extends AbstractCourseAuthoringTest {

    private InstructorCourseResponse threeModuleCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Ordered", CourseStatus.PUBLISHED,
                        module("Introduction", lesson("L1")),
                        module("Basics", lesson("L2")),
                        module("Advanced", lesson("L3"))));
    }

    @Nested
    @DisplayName("the reorder command")
    class Reorder {

        @Test
        @DisplayName("puts the modules in the requested order, and the database agrees")
        void reorderSurvivesADatabaseReload() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);

            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(2), ids.get(0), ids.get(1))));

            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Advanced", "Introduction", "Basics");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("leaves the course published and its content untouched")
        void reorderTouchesNothingElse() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);

            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(1), ids.get(2), ids.get(0))));

            var reloaded = reload(course.getId());
            assertThat(reloaded.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(reloaded.getTitle()).isEqualTo("Ordered");
            assertThat(lessonRepository.countByCourseId(course.getId())).isEqualTo(3);
        }

        @Test
        @DisplayName("a reorder does not carry the rest of the course, so it cannot undo another edit")
        void reorderDoesNotOverwriteConcurrentMetadataEdits() {
            var course = threeModuleCourse();
            var staleIds = moduleIdsOf(course);

            // One tab renames the course...
            var rename = echoOf(course);
            rename.setTitle("Renamed by the other tab");
            courseService.updateCourse(instructorUser, course.getId(), rename);

            // ...while another, still holding the old copy, drags a module.
            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(staleIds.get(2), staleIds.get(1), staleIds.get(0))));

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Renamed by the other tab");
            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Advanced", "Basics", "Introduction");
        }

        @Test
        @DisplayName("asking for the order the course is already in is harmless and silent")
        void aNoOpReorderChangesNothing() {
            var course = threeModuleCourse();
            var before = reload(course.getId()).getContentUpdatedAt();

            courseService.reorderModules(instructorUser, course.getId(), order(moduleIdsOf(course)));

            var after = reload(course.getId());
            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Introduction", "Basics", "Advanced");
            assertThat(after.getContentUpdatedAt())
                    .as("a reorder that reorders nothing must not tell learners the course changed")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("the same reorder sent twice ends in the same place")
        void reorderIsIdempotent() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);
            var requested = List.of(ids.get(1), ids.get(2), ids.get(0));

            courseService.reorderModules(instructorUser, course.getId(), order(requested));
            courseService.reorderModules(instructorUser, course.getId(), order(requested));

            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Basics", "Advanced", "Introduction");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("a one-module course can be reordered, pointlessly but safely")
        void singleModuleReorder() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Alone", CourseStatus.PUBLISHED, module("Only", lesson("L1"))));

            courseService.reorderModules(instructorUser, course.getId(), order(moduleIdsOf(course)));

            assertThat(persistedModulePositions(course.getId())).containsExactly(0);
        }

        @Test
        @DisplayName("a course with no modules accepts an empty reorder")
        void emptyCourseReorder() {
            var course = courseService.createCourse(instructorUser,
                    CourseRequest.builder()
                            .title("No modules yet")
                            .description("A draft that has not been filled in")
                            .structure(CourseStructure.MODULES)
                            .modules(List.of())
                            .status(CourseStatus.DRAFT)
                            .build());

            courseService.reorderModules(instructorUser, course.getId(), order(List.of()));

            assertThat(persistedModuleTitles(course.getId())).isEmpty();
        }

        @Test
        @DisplayName("first to last, last to first, and a middle swap all land correctly")
        void everyShapeOfMove() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Five", CourseStatus.PUBLISHED,
                            module("A", lesson("a")), module("B", lesson("b")), module("C", lesson("c")),
                            module("D", lesson("d")), module("E", lesson("e"))));
            var ids = moduleIdsOf(course);

            // first -> last
            var moved = new ArrayList<>(ids);
            moved.add(moved.remove(0));
            courseService.reorderModules(instructorUser, course.getId(), order(moved));
            assertThat(persistedModuleTitles(course.getId())).containsExactly("B", "C", "D", "E", "A");

            // last -> first
            var current = moduleIdsOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            var back = new ArrayList<>(current);
            back.add(0, back.remove(back.size() - 1));
            courseService.reorderModules(instructorUser, course.getId(), order(back));
            assertThat(persistedModuleTitles(course.getId())).containsExactly("A", "B", "C", "D", "E");

            // middle -> middle
            var middle = new ArrayList<>(moduleIdsOf(courseService.getCourseForEditing(instructorUser, course.getId())));
            middle.add(3, middle.remove(1));
            courseService.reorderModules(instructorUser, course.getId(), order(middle));
            assertThat(persistedModuleTitles(course.getId())).containsExactly("A", "C", "D", "B", "E");

            // full reverse
            var reversed = new ArrayList<>(moduleIdsOf(courseService.getCourseForEditing(instructorUser, course.getId())));
            java.util.Collections.reverse(reversed);
            courseService.reorderModules(instructorUser, course.getId(), order(reversed));
            assertThat(persistedModuleTitles(course.getId())).containsExactly("E", "B", "D", "C", "A");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2, 3, 4);
        }
    }

    @Nested
    @DisplayName("a reorder the course cannot honour is refused whole")
    class InvalidOrders {

        @Test
        void nullList() {
            var course = threeModuleCourse();
            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(), order(null)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleOrderRequired");
        }

        @Test
        void nullId() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);
            List<Long> withNull = Arrays.asList(ids.get(0), null, ids.get(2));

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(), order(withNull)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleOrderNullId");
            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Introduction", "Basics", "Advanced");
        }

        @Test
        void duplicateId() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(0), ids.get(0), ids.get(1)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleOrderDuplicate");
        }

        @Test
        void aMissingModule() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(0), ids.get(1)))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleOrderIncomplete");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }

        @Test
        void anUnknownModule() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(0), ids.get(1), 9_999_999L))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleNotInCourse");
        }

        @Test
        @DisplayName("a module belonging to another instructor's course is not merely absent — it is unreachable")
        void aModuleFromAnotherCourse() {
            var course = threeModuleCourse();
            var other = courseService.createCourse(newInstructorUser(),
                    modularCourse("Someone else's", CourseStatus.PUBLISHED, module("Theirs", lesson("x"))));

            var ids = moduleIdsOf(course);
            var injected = List.of(ids.get(0), ids.get(1), moduleIdsOf(other).get(0));

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(), order(injected)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleNotInCourse");

            assertThat(persistedModuleTitles(other.getId()))
                    .as("the other instructor's course must not have been touched")
                    .containsExactly("Theirs");
        }

        @Test
        @DisplayName("an empty list for a course that has modules is a stale client, not an instruction")
        void emptyListForANonEmptyCourse() {
            var course = threeModuleCourse();

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(), order(List.of())))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.moduleOrderIncomplete");
            assertThat(persistedModuleTitles(course.getId())).hasSize(3);
        }

        @Test
        @DisplayName("a reorder built before a module was deleted elsewhere is rejected, not half-applied")
        void aStaleReorderAfterADelete() {
            var course = threeModuleCourse();
            var staleIds = moduleIdsOf(course);

            var withoutBasics = echoOf(course);
            withoutBasics.setModules(List.of(withoutBasics.getModules().get(0), withoutBasics.getModules().get(2)));
            courseService.updateCourse(instructorUser, course.getId(), withoutBasics);

            assertThatThrownBy(() -> courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(staleIds.get(2), staleIds.get(1), staleIds.get(0)))))
                    .isInstanceOf(BusinessException.class);

            assertThat(persistedModuleTitles(course.getId())).containsExactly("Introduction", "Advanced");
        }
    }

    @Nested
    @DisplayName("positions stay contiguous through every structural change")
    class Positions {

        @Test
        @DisplayName("the first module of an empty course takes position 0")
        void firstModuleTakesPositionZero() {
            var course = courseService.createCourse(instructorUser,
                    CourseRequest.builder()
                            .title("Starts empty")
                            .description("A draft with no modules in it at all")
                            .structure(CourseStructure.MODULES)
                            .modules(List.of())
                            .status(CourseStatus.DRAFT)
                            .build());

            var withFirst = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            withFirst.setModules(List.of(module("First", lesson("L1"))));
            courseService.updateCourse(instructorUser, course.getId(), withFirst);

            assertThat(persistedModulePositions(course.getId())).containsExactly(0);
        }

        @Test
        @DisplayName("an added module takes the next position, never a colliding one")
        void addedModuleTakesTheNextPosition() {
            var course = threeModuleCourse();

            var withFourth = echoOf(course);
            var modules = new ArrayList<>(withFourth.getModules());
            modules.add(module("Fourth", lesson("L4")));
            withFourth.setModules(modules);
            courseService.updateCourse(instructorUser, course.getId(), withFourth);

            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2, 3);
            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Introduction", "Basics", "Advanced", "Fourth");
        }

        @Test
        @DisplayName("deleting a module from the middle closes the gap it left")
        void deletingAModuleNormalisesTheRest() {
            var course = threeModuleCourse();

            var withoutMiddle = echoOf(course);
            withoutMiddle.setModules(List.of(withoutMiddle.getModules().get(0), withoutMiddle.getModules().get(2)));
            courseService.updateCourse(instructorUser, course.getId(), withoutMiddle);

            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1);
            assertThat(persistedModuleTitles(course.getId())).containsExactly("Introduction", "Advanced");
        }

        @Test
        @DisplayName("the aggregate save orders modules by their position in the payload")
        void theAggregateSaveAlsoReordersDeterministically() {
            var course = threeModuleCourse();

            var reordered = echoOf(course);
            var modules = reordered.getModules();
            reordered.setModules(List.of(modules.get(2), modules.get(0), modules.get(1)));
            courseService.updateCourse(instructorUser, course.getId(), reordered);

            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Advanced", "Introduction", "Basics");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }
    }

    @Nested
    @DisplayName("every read path returns the same order")
    class ReadPaths {

        @Test
        void editorAndLearnerViewsAgreeWithTheDatabase() {
            var course = threeModuleCourse();
            var ids = moduleIdsOf(course);
            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(2), ids.get(0), ids.get(1))));

            var editorView = courseService.getCourseForEditing(instructorUser, course.getId());
            assertThat(editorView.getModules()).extracting(m -> m.getTitle())
                    .containsExactly("Advanced", "Introduction", "Basics");

            var learnerView = courseService.getCourseDetails(
                    newStudentUser(), course.getId(), com.manara.backend.course.dto.CourseViewMode.DISCOVER);
            assertThat(learnerView.getModules()).extracting(m -> m.getTitle())
                    .containsExactly("Advanced", "Introduction", "Basics");

            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Advanced", "Introduction", "Basics");
        }
    }
}
