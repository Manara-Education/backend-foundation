package com.manara.backend.course.integration;

import com.manara.backend.common.exception.ConflictException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonOrder;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.moduleWithExam;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.order;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.quiz;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Two instructor sessions editing one course, and what the server does about it.
 *
 * <h2>The failure this closes</h2>
 * The aggregate {@code PUT} is full replacement, so a payload built from a copy of the course
 * loaded an hour ago is not "an edit to one field" — it is an hour-old course, and applying it puts
 * every field back the way that copy remembers them. Two open tabs were enough to lose real work:
 *
 * <ul>
 *   <li>Tab B switched a course from {@code FREE} to {@code PURCHASE} at 199 and saved. Tab A then
 *       renamed a lesson from a copy loaded before that, and the course went back to free. Both
 *       requests were answered {@code 200}.
 *   <li>Tab B retitled a module exam. Tab A's stale save wiped it. {@code 200} again.
 *   <li>Two saves fired together both succeeded, and one of the two edits simply did not exist
 *       afterwards.
 * </ul>
 *
 * <p>An update now has to name the revision it was built from. The check and the increment happen
 * under a lock on the course row, so two requests quoting the same revision cannot both pass it —
 * the guarantee is a database one, not a hopeful read-then-write.
 */
class CourseRevisionConcurrencyTest extends AbstractCourseAuthoringTest {

    /** The editor's model, as a second tab loading the course right now would receive it. */
    private InstructorCourseResponse asLoadedNow(Long courseId) {
        return courseService.getCourseForEditing(instructorUser, courseId);
    }

    private void assertStale(Runnable save) {
        assertThatThrownBy(save::run)
                .isInstanceOf(ConflictException.class)
                .satisfies(thrown -> assertThat(((ConflictException) thrown).getErrorCode())
                        .isEqualTo(ErrorCode.COURSE_VERSION_CONFLICT));
    }

    // ── Scenario A ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a stale save cannot revert a quiz edit")
    class QuizEdit {

        @Test
        @DisplayName("Tab B edits a module exam; Tab A's stale aggregate is refused and writes nothing")
        void aStaleSaveCannotWipeAModuleExam() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Exams", CourseStatus.PUBLISHED,
                            moduleWithExam("One", quiz("Module Exam"), lesson("L1"))));

            // Tab A loads and holds the course.
            var tabA = echoOf(course);

            // Tab B retitles the module exam and saves.
            var tabB = echoOf(asLoadedNow(course.getId()));
            tabB.getModules().getFirst().getQuiz().setTitle("Module Exam, revised");
            courseService.updateCourse(instructorUser, course.getId(), tabB);

            // Tab A saves an unrelated edit from its old copy — which still carries the old exam.
            tabA.setTitle("Renamed from a stale tab");
            assertStale(() -> courseService.updateCourse(instructorUser, course.getId(), tabA));

            // Tab B's edit stands, and Tab A's wrote nothing at all.
            var current = asLoadedNow(course.getId());
            assertThat(current.getModules().getFirst().getQuiz().getTitle())
                    .isEqualTo("Module Exam, revised");
            assertThat(current.getTitle()).isEqualTo("Exams");
        }
    }

    // ── Scenario B ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a stale save cannot revert pricing")
    class Pricing {

        @Test
        @DisplayName("Tab B sells the course; Tab A's stale lesson rename cannot make it free again")
        void aStaleSaveCannotRevertPricing() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Priced", CourseStatus.PUBLISHED, lesson("L1"), lesson("L2")));
            assertThat(reload(course.getId()).getAccessType()).isEqualTo(CourseAccessType.FREE);

            var tabA = echoOf(course);

            var tabB = echoOf(asLoadedNow(course.getId()));
            tabB.setAccessType(CourseAccessType.PURCHASE);
            tabB.setPurchasePrice(new BigDecimal("199.00"));
            courseService.updateCourse(instructorUser, course.getId(), tabB);

            // Tab A renames a lesson. Its copy still says FREE, with no price.
            tabA.getLessons().getFirst().setTitle("L1, renamed");
            assertStale(() -> courseService.updateCourse(instructorUser, course.getId(), tabA));

            var after = reload(course.getId());
            assertThat(after.getAccessType()).isEqualTo(CourseAccessType.PURCHASE);
            assertThat(after.getPurchasePrice()).isEqualByComparingTo("199.00");
            assertThat(persistedRootLessonTitles(course.getId())).containsExactly("L1", "L2");
        }

        /**
         * A repricing is invisible to learners and still has to move the revision.
         *
         * <p>It never stamps {@code contentUpdatedAt} and never lights anybody's badge — but it
         * does change the stored aggregate, so a tab that has not seen it is holding a course that
         * would undo it. Both halves are asserted here because they pull in opposite directions.
         */
        @Test
        @DisplayName("repricing moves the revision without announcing anything")
        void repricingIsSilentButNotInvisible() {
            var course = courseService.publish(instructorUser, courseService.createCourse(instructorUser,
                    flatCourse("Quiet", CourseStatus.PUBLISHED, lesson("L1"))).getId());
            var before = reload(course.getId());

            var repricing = echoOf(course);
            repricing.setAccessType(CourseAccessType.PURCHASE);
            repricing.setPurchasePrice(new BigDecimal("250.00"));
            courseService.updateCourse(instructorUser, course.getId(), repricing);

            var after = reload(course.getId());
            assertThat(after.getRevision()).isEqualTo(before.getRevision() + 1);
            assertThat(after.getContentUpdatedAt()).isEqualTo(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isFalse();
        }
    }

    // ── Scenario C ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("two saves quoting one revision")
    class SimultaneousSaves {

        /**
         * The check and the increment are one step, or they are no protection at all.
         *
         * <p>Both threads read revision {@code n} before either commits. Without the row lock both
         * find it current and both write, which is the same lost update the revision was added to
         * stop — merely made narrower and harder to reproduce. Exactly one may win.
         */
        @Test
        @DisplayName("exactly one wins, the other is told, and no mixture is stored")
        void oneWinsAndOneIsRefused() throws Exception {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Contended", CourseStatus.PUBLISHED, lesson("L1")));

            var first = echoOf(course);
            first.setTitle("Won by the first");
            var second = echoOf(course);
            second.setDescription("Won by the second, a description long enough to be real");

            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            int accepted;
            try {
                var a = pool.submit(() -> attempt(course.getId(), first));
                var b = pool.submit(() -> attempt(course.getId(), second));
                start.countDown();
                accepted = (a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0);
            } finally {
                pool.shutdownNow();
            }

            assertThat(accepted).as("exactly one of two saves from the same revision may commit").isOne();

            // And what is stored is one of the two payloads whole, never half of each.
            var after = reload(course.getId());
            boolean firstWon = after.getTitle().equals("Won by the first");
            if (firstWon) {
                assertThat(after.getDescription()).isEqualTo(course.getDescription());
            } else {
                assertThat(after.getTitle()).isEqualTo("Contended");
                assertThat(after.getDescription())
                        .isEqualTo("Won by the second, a description long enough to be real");
            }
            assertThat(after.getRevision()).isEqualTo(course.getRevision() + 1);
        }

        private boolean attempt(Long courseId, CourseRequest request) {
            try {
                courseService.updateCourse(instructorUser, courseId, request);
                return true;
            } catch (ConflictException refused) {
                assertThat(refused.getErrorCode()).isEqualTo(ErrorCode.COURSE_VERSION_CONFLICT);
                return false;
            }
        }
    }

    // ── Scenarios D and E ───────────────────────────────────────────────────

    @Nested
    @DisplayName("one editor, reordering and then saving")
    class ReorderThenSave {

        /**
         * The case the mechanism must <em>not</em> break: one editor, doing two ordinary things.
         *
         * <p>A reorder is an accepted change, so it moves the revision — and it answers with the
         * course, revision included. An editor that adopts what it is given can carry straight on;
         * one that kept quoting the revision it loaded with would conflict with nobody but itself.
         */
        @Test
        @DisplayName("a reorder then an aggregate save both succeed when the editor adopts the answer")
        void reorderThenSaveBothSucceed() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Sequential", CourseStatus.PUBLISHED,
                            module("One", lesson("L1")), module("Two", lesson("L2")),
                            module("Three", lesson("L3"))));
            var ids = moduleIdsOf(course);

            // The reorder answers with the reordered course, at its new revision.
            var afterReorder = courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(2), ids.get(0), ids.get(1))));
            assertThat(afterReorder.getRevision()).isEqualTo(course.getRevision() + 1);

            var save = echoOf(afterReorder);
            save.setTitle("Sequential, renamed");
            courseService.updateCourse(instructorUser, course.getId(), save);

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Sequential, renamed");
            assertThat(persistedModuleTitles(course.getId()))
                    .containsExactly("Three", "One", "Two");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }

        @Test
        @DisplayName("a nested lesson reorder propagates its revision the same way")
        void nestedReorderPropagatesItsRevision() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Nested", CourseStatus.PUBLISHED,
                            module("Only", lesson("A1"), lesson("A2"), lesson("A3"))));
            var moduleId = moduleIdsOf(course).getFirst();
            var lessonIds = moduleLessonIdsOf(course, 0);

            var afterReorder = courseService.reorderModuleLessons(instructorUser, course.getId(), moduleId,
                    lessonOrder(List.of(lessonIds.get(2), lessonIds.get(1), lessonIds.get(0))));

            var save = echoOf(afterReorder);
            save.setDescription("Renamed after the drag, long enough to be a real description");
            courseService.updateCourse(instructorUser, course.getId(), save);

            assertThat(persistedModuleLessonTitles(course.getId(), moduleId))
                    .containsExactly("A3", "A2", "A1");
        }

        /** Scenario E: the same drag, but the aggregate save is built from before it. */
        @Test
        @DisplayName("an aggregate save built before the drag is refused, and the order stands")
        void aSaveBuiltBeforeTheDragIsRefused() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Dragged", CourseStatus.PUBLISHED,
                            module("One", lesson("L1")), module("Two", lesson("L2")),
                            module("Three", lesson("L3"))));
            var staleCopy = echoOf(course);
            var ids = moduleIdsOf(course);

            courseService.reorderModules(instructorUser, course.getId(),
                    order(List.of(ids.get(1), ids.get(2), ids.get(0))));

            staleCopy.setTitle("Renamed from before the drag");
            assertStale(() -> courseService.updateCourse(instructorUser, course.getId(), staleCopy));

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Dragged");
            assertThat(persistedModuleTitles(course.getId())).containsExactly("Two", "Three", "One");
            assertThat(persistedModulePositions(course.getId())).containsExactly(0, 1, 2);
        }
    }

    @Nested
    @DisplayName("what a refused save leaves behind")
    class RejectedSavesAreNotMutations {

        @Test
        @DisplayName("nothing: not the content, not the version, not the badge")
        void aRefusedSaveChangesNothing() {
            var course = courseService.publish(instructorUser, courseService.createCourse(instructorUser,
                    modularCourse("Untouched", CourseStatus.PUBLISHED,
                            module("One", lesson("L1")))).getId());
            var staleCopy = echoOf(course);

            var newer = echoOf(course);
            newer.setTitle("The newer edit");
            courseService.updateCourse(instructorUser, course.getId(), newer);
            var afterNewer = reload(course.getId());

            staleCopy.setTitle("The stale edit");
            assertStale(() -> courseService.updateCourse(instructorUser, course.getId(), staleCopy));

            var afterRefusal = reload(course.getId());
            assertThat(afterRefusal.getTitle()).isEqualTo("The newer edit");
            assertThat(afterRefusal.getRevision()).isEqualTo(afterNewer.getRevision());
            assertThat(afterRefusal.getContentUpdatedAt()).isEqualTo(afterNewer.getContentUpdatedAt());
            assertThat(afterRefusal.hasUpdatesSincePublish()).isEqualTo(afterNewer.hasUpdatesSincePublish());
            assertThat(afterRefusal.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        }

        @Test
        @DisplayName("another instructor's course is still not found, whatever revision is guessed")
        void aRevisionIsNotAWayIntoSomebodyElsesCourse() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Private", CourseStatus.PUBLISHED, lesson("L1")));
            var stranger = newInstructorUser();

            var guess = echoOf(course);
            guess.setTitle("Taken over");
            assertThatThrownBy(() -> courseService.updateCourse(stranger, course.getId(), guess))
                    .isInstanceOf(com.manara.backend.common.exception.BusinessException.class)
                    .hasMessage("error.course.notOwner");

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Private");
        }
    }
}
