package com.manara.backend.course.integration;

import com.manara.backend.course.dto.ContentChangeState;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.ContentEntityType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonOrder;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.quiz;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one learner is told about a course they joined — and, just as importantly, what a learner
 * who joined later is not.
 *
 * <p>Every assertion here is about a <em>particular</em> student. That is the whole subject: the
 * course-level badge and every row of the curriculum are answers to "has this changed since
 * <em>you</em> joined", so a test that only ever looks at one learner cannot tell a correct
 * implementation from one that marks everything updated for everybody.
 *
 * <p>Enrollments are placed in time rather than slept into position. {@code enrolled_at} is
 * {@code updatable = false} in the entity and stays that way, so these tests move it by SQL: the
 * two cases the feature turns on are "enrolled before the edit" and "enrolled after it", and there
 * is no honest way to arrange the second one otherwise.
 */
class StudentCourseUpdateTest extends AbstractCourseAuthoringTest {

    private static final LocalDateTime LONG_BEFORE = LocalDateTime.now().minusDays(30);
    private static final LocalDateTime LONG_AFTER = LocalDateTime.now().plusDays(30);

    private InstructorCourseResponse publishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Live course", CourseStatus.PUBLISHED,
                        module("One", lesson("L1"), lesson("L2")),
                        module("Two", lesson("L3"))));
    }

    /**
     * A learner who joined a course that was already live — the ordinary case.
     *
     * <p>The course is pushed back before the enrollment as well as the enrollment before the edit.
     * A course created inside the test method exists as of now, so an enrollment dated a month ago
     * would predate it, and every row would correctly report itself as content this learner has
     * never seen. That is a real answer to a nonsensical question, not the scenario being tested.
     */
    private User earlyLearner(Long courseId) {
        courseExistedSince(courseId, LONG_BEFORE.minusDays(1));
        User student = newStudentUser();
        enrolledAt(enroll(student, courseId).getId(), LONG_BEFORE);
        return student;
    }

    /** A learner whose enrollment is dated after everything a test does — the "joined later" case. */
    private User lateLearner(Long courseId) {
        User student = newStudentUser();
        enrolledAt(enroll(student, courseId).getId(), LONG_AFTER);
        return student;
    }

    private List<LessonResponse> lessonsOf(CourseDetailsResponse details) {
        return details.getModules().stream().flatMap(module -> module.getLessons().stream()).toList();
    }

    private LessonResponse lessonTitled(CourseDetailsResponse details, String title) {
        return lessonsOf(details).stream()
                .filter(lesson -> title.equals(lesson.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no lesson titled " + title));
    }

    // =========================================================================

    @Nested
    @DisplayName("the course badge is per enrollment")
    class CourseBadge {

        @Test
        void lightsUpForAStudentWhoEnrolledBeforeTheChange() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.setTitle("Live course, renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            assertThat(detailsFor(student, course.getId()).getCourse().getHasUpdatesSinceEnrollment()).isTrue();
            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isTrue();
        }

        @Test
        void staysDarkForAStudentWhoEnrolledAfterIt() {
            var course = publishedCourse();

            var request = echoOf(course);
            request.setTitle("Live course, renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            // Enrolled after the edit: they bought the version that already contained it.
            User student = lateLearner(course.getId());

            assertThat(detailsFor(student, course.getId()).getCourse().getHasUpdatesSinceEnrollment()).isFalse();
            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isFalse();
        }

        /**
         * The single test this whole feature exists for. One course, one edit, two learners, two
         * different answers — which no implementation storing a boolean on the course can produce.
         */
        @Test
        void twoStudentsOfOneCourseSeeDifferentThings() {
            var course = publishedCourse();
            User before = earlyLearner(course.getId());

            var request = echoOf(course);
            request.setDescription("A different description entirely, longer than before");
            courseService.updateCourse(instructorUser, course.getId(), request);

            User after = lateLearner(course.getId());

            assertThat(cardFor(before, course.getId()).getHasUpdatesSinceEnrollment()).isTrue();
            assertThat(cardFor(after, course.getId()).getHasUpdatesSinceEnrollment()).isFalse();
        }

        @Test
        void staysDarkWhenNothingChanged() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            // The editor's own payload, sent back untouched.
            courseService.updateCourse(instructorUser, course.getId(), echoOf(course));

            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isFalse();
        }

        @Test
        void viewingTheCourseDoesNotClearItAndDoesNotMoveTheEnrollment() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.setTitle("Renamed once");
            courseService.updateCourse(instructorUser, course.getId(), request);

            var enrollment = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(student).getId())
                    .orElseThrow();
            LocalDateTime before = enrollment.getEnrolledAt();

            detailsFor(student, course.getId());
            detailsFor(student, course.getId());

            var after = enrollmentRepository.findById(enrollment.getId()).orElseThrow();
            assertThat(after.getEnrolledAt()).isEqualTo(before);
            // Still updated: only an instructor re-publishing settles a version, never a page view.
            assertThat(detailsFor(student, course.getId()).getCourse().getHasUpdatesSinceEnrollment()).isTrue();
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("pricing is not content")
    class Pricing {

        @Test
        void raisingThePriceTellsExistingStudentsNothing() {
            var course = courseService.createCourse(instructorUser,
                    CourseAuthoringFixtures.modularCourse("Paid course", CourseStatus.PUBLISHED,
                            module("One", lesson("L1"))));
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.setAccessType(com.manara.backend.course.model.CourseAccessType.PURCHASE);
            request.setPurchasePrice(new java.math.BigDecimal("700"));
            courseService.updateCourse(instructorUser, course.getId(), request);

            var card = cardFor(student, course.getId());
            assertThat(card.getHasUpdatesSinceEnrollment()).isFalse();
            assertThat(detailsFor(student, course.getId()).getCourse().getHasUpdatesSinceEnrollment()).isFalse();
        }

        @Test
        void andLeavesTheirEnrollmentAndAccessExactlyWhereTheyWere() {
            var course = courseService.createCourse(instructorUser,
                    CourseAuthoringFixtures.modularCourse("Paid course", CourseStatus.PUBLISHED,
                            module("One", lesson("L1"))));
            User student = earlyLearner(course.getId());
            var student_ = studentProfileOf(student);

            var enrollmentBefore = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), student_.getId()).orElseThrow();
            var entitlementBefore = courseEntitlementRepository
                    .findByCourseIdAndStudentId(course.getId(), student_.getId()).orElseThrow();

            var request = echoOf(course);
            request.setAccessType(com.manara.backend.course.model.CourseAccessType.PURCHASE);
            request.setPurchasePrice(new java.math.BigDecimal("700"));
            courseService.updateCourse(instructorUser, course.getId(), request);

            var enrollmentAfter = enrollmentRepository.findById(enrollmentBefore.getId()).orElseThrow();
            var entitlementAfter = courseEntitlementRepository.findById(entitlementBefore.getId()).orElseThrow();

            assertThat(enrollmentAfter.getEnrolledAt()).isEqualTo(enrollmentBefore.getEnrolledAt());
            assertThat(enrollmentAfter.getEnrolled()).isTrue();
            assertThat(entitlementAfter.getExpiresAt()).isNull();
            assertThat(entitlementAfter.isActiveAt(LocalDateTime.now())).isTrue();
            // Nothing in the curriculum moved either.
            assertThat(lessonsOf(detailsFor(student, course.getId())))
                    .allSatisfy(lesson -> assertThat(lesson.getChange().getState())
                            .isEqualTo(ContentChangeState.UNCHANGED));
        }

        @Test
        void aPriceChangeFollowedByARealEditStillReportsTheEdit() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var priceOnly = echoOf(course);
            priceOnly.setAccessType(com.manara.backend.course.model.CourseAccessType.PURCHASE);
            priceOnly.setPurchasePrice(new java.math.BigDecimal("700"));
            var afterPricing = courseService.updateCourse(instructorUser, course.getId(), priceOnly);
            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isFalse();

            var contentEdit = echoOf(afterPricing);
            contentEdit.getModules().getFirst().getLessons().getFirst().setDescription("Rewritten body");
            courseService.updateCourse(instructorUser, course.getId(), contentEdit);

            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isTrue();
            assertThat(lessonTitled(detailsFor(student, course.getId()), "L1").getChange().getState())
                    .isEqualTo(ContentChangeState.UPDATED);
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("new content is NEW, edited content is UPDATED")
    class ItemState {

        @Test
        void anAddedLessonIsNewToAnExistingStudentAndOrdinaryToALaterOne() {
            var course = publishedCourse();
            User before = earlyLearner(course.getId());

            var request = echoOf(course);
            var lessons = new ArrayList<>(request.getModules().getFirst().getLessons());
            lessons.add(lesson("L4"));
            request.getModules().getFirst().setLessons(lessons);
            courseService.updateCourse(instructorUser, course.getId(), request);

            User after = lateLearner(course.getId());

            var forBefore = lessonTitled(detailsFor(before, course.getId()), "L4");
            assertThat(forBefore.getChange().getState()).isEqualTo(ContentChangeState.NEW);
            assertThat(forBefore.getChange().getSummary()).isEqualTo("New lesson added");

            // Same lesson, same row, different reader: it was already there when they joined.
            assertThat(lessonTitled(detailsFor(after, course.getId()), "L4").getChange().getState())
                    .isEqualTo(ContentChangeState.UNCHANGED);
        }

        @Test
        void anEditedLessonIsUpdatedAndItsUntouchedSiblingsAreNot() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().getLessons().getFirst().setDescription("Rewritten body");
            courseService.updateCourse(instructorUser, course.getId(), request);

            var details = detailsFor(student, course.getId());
            assertThat(lessonTitled(details, "L1").getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(lessonTitled(details, "L1").getChange().getSummary()).isEqualTo("Lesson content updated");

            // The point of item-level tracking: one lesson changed, so one lesson says so.
            assertThat(lessonTitled(details, "L2").getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
            assertThat(lessonTitled(details, "L3").getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
        }

        @Test
        void aNewLessonIsNeverAlsoReportedAsUpdated() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            var lessons = new ArrayList<>(request.getModules().getFirst().getLessons());
            lessons.add(lesson("L4"));
            request.getModules().getFirst().setLessons(lessons);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var created = lessonTitled(detailsFor(student, course.getId()), "L4");
            assertThat(created.getChange().getState()).isEqualTo(ContentChangeState.NEW);
            // NEW wins by construction, not by check order: a created lesson's content version
            // starts equal to its creation instant and the request that created it never moves it.
            var stored = lessonRepository.findById(created.getId()).orElseThrow();
            assertThat(stored.getContentUpdatedAt()).isEqualTo(stored.getCreatedAt());
        }

        @Test
        void anAddedModuleIsNewAndTheModulesAroundItAreNot() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            var modules = new ArrayList<>(request.getModules());
            modules.add(module("Three", lesson("L9")));
            request.setModules(modules);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var details = detailsFor(student, course.getId());
            assertThat(details.getModules()).extracting(m -> m.getTitle() + ":" + m.getChange().getState())
                    .containsExactly("One:UNCHANGED", "Two:UNCHANGED", "Three:NEW");
            assertThat(details.getModules().getLast().getChange().getSummary()).isEqualTo("New section added");
        }

        @Test
        void anEditedModuleIsUpdatedWithoutMarkingItsLessons() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().setTitle("One, renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            var details = detailsFor(student, course.getId());
            assertThat(details.getModules().getFirst().getChange().getState())
                    .isEqualTo(ContentChangeState.UPDATED);
            // A module whose title changed did not change any of its lessons.
            assertThat(details.getModules().getFirst().getLessons())
                    .allSatisfy(l -> assertThat(l.getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("quizzes and exams")
    class Assessments {

        @Test
        void anEditedLessonQuizMarksTheQuizAndNotTheLesson() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Live course", CourseStatus.PUBLISHED,
                            module("One", withQuiz(lesson("L1"), quiz("Lesson quiz")))));
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().getLessons().getFirst().getQuiz().setPassingScore(90);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var lesson = lessonTitled(detailsFor(student, course.getId()), "L1");
            assertThat(lesson.getQuiz().getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(lesson.getQuiz().getChange().getSummary()).isEqualTo("Quiz updated");
            // The video did not move, so the lesson row does not claim it did.
            assertThat(lesson.getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
        }

        @Test
        void anAddedFinalExamIsNewAndIsCalledAnExamRatherThanAQuiz() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.setFinalQuiz(quiz("Final exam"));
            courseService.updateCourse(instructorUser, course.getId(), request);

            var change = detailsFor(student, course.getId()).getFinalQuiz().getChange();
            assertThat(change.getState()).isEqualTo(ContentChangeState.NEW);
            // One table, two words. A course's quiz is an exam to the person sitting it.
            assertThat(change.getSummary()).isEqualTo("New exam added");
        }

        @Test
        void anEditedModuleExamIsUpdated() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Live course", CourseStatus.PUBLISHED,
                            withExam(module("One", lesson("L1")), quiz("Module exam"))));
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().getQuiz().setTitle("Module exam, renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            var change = detailsFor(student, course.getId()).getModules().getFirst().getQuiz().getChange();
            assertThat(change.getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(change.getSummary()).isEqualTo("Exam details updated");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("moving, reordering and removing")
    class Structure {

        @Test
        void aLessonMovedBetweenModulesSaysWhereItCameFrom() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            var moved = request.getModules().getFirst().getLessons().getLast();   // L2, in module One
            request.getModules().getFirst().setLessons(
                    List.of(request.getModules().getFirst().getLessons().getFirst()));
            var second = new ArrayList<>(request.getModules().getLast().getLessons());
            second.add(moved);
            request.getModules().getLast().setLessons(second);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var change = lessonTitled(detailsFor(student, course.getId()), "L2").getChange();
            assertThat(change.getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(change.getSummary()).isEqualTo("Lesson moved from One to Two");
        }

        @Test
        void reorderingMarksOnlyTheLessonsThatActuallyMoved() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            Long moduleId = course.getModules().getFirst().getId();
            List<Long> reversed = new ArrayList<>(moduleLessonIdsOf(course, 0));
            java.util.Collections.reverse(reversed);
            courseService.reorderModuleLessons(instructorUser, course.getId(), moduleId, lessonOrder(reversed));

            var details = detailsFor(student, course.getId());
            assertThat(lessonTitled(details, "L1").getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(lessonTitled(details, "L2").getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            // A lesson in the other module was not in the reordered scope and did not move.
            assertThat(lessonTitled(details, "L3").getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
        }

        @Test
        void aReorderThatChangesNothingTellsNobodyAnything() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            Long moduleId = course.getModules().getFirst().getId();
            courseService.reorderModuleLessons(instructorUser, course.getId(), moduleId,
                    lessonOrder(moduleLessonIdsOf(course, 0)));

            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isFalse();
        }

        @Test
        void aRemovedLessonIsListedSeparatelyBecauseThereIsNoRowLeftToMark() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().setLessons(
                    List.of(request.getModules().getFirst().getLessons().getFirst()));   // drop L2
            courseService.updateCourse(instructorUser, course.getId(), request);

            var details = detailsFor(student, course.getId());
            assertThat(lessonsOf(details)).extracting(LessonResponse::getTitle).doesNotContain("L2");
            assertThat(details.getRemovedContent()).singleElement().satisfies(removed -> {
                assertThat(removed.getEntityType()).isEqualTo(ContentEntityType.LESSON);
                assertThat(removed.getTitle()).isEqualTo("L2");
                assertThat(removed.getSummary()).isEqualTo("Lesson removed");
            });
        }

        @Test
        void aStudentWhoJoinedAfterTheRemovalNeverHearsAboutIt() {
            var course = publishedCourse();

            var request = echoOf(course);
            request.getModules().getFirst().setLessons(
                    List.of(request.getModules().getFirst().getLessons().getFirst()));
            courseService.updateCourse(instructorUser, course.getId(), request);

            User after = lateLearner(course.getId());
            assertThat(detailsFor(after, course.getId()).getRemovedContent()).isEmpty();
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("several changes at once")
    class Compound {

        @Test
        void everyItemReportsItsOwnStateAfterFourSeparateEdits() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            // 1 — a lesson's body is rewritten.
            var first = echoOf(course);
            first.getModules().getFirst().getLessons().getFirst().setDescription("Rewritten body");
            var afterFirst = courseService.updateCourse(instructorUser, course.getId(), first);

            // 2 — a lesson is added.
            var second = echoOf(afterFirst);
            var lessons = new ArrayList<>(second.getModules().getLast().getLessons());
            lessons.add(lesson("L4"));
            second.getModules().getLast().setLessons(lessons);
            var afterSecond = courseService.updateCourse(instructorUser, course.getId(), second);

            // 3 — a final exam appears.
            var third = echoOf(afterSecond);
            third.setFinalQuiz(quiz("Final exam"));
            var afterThird = courseService.updateCourse(instructorUser, course.getId(), third);

            // 4 — a module is renamed.
            var fourth = echoOf(afterThird);
            fourth.getModules().getFirst().setTitle("One, renamed");
            courseService.updateCourse(instructorUser, course.getId(), fourth);

            var details = detailsFor(student, course.getId());
            assertThat(details.getCourse().getHasUpdatesSinceEnrollment()).isTrue();
            assertThat(lessonTitled(details, "L1").getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(lessonTitled(details, "L2").getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
            assertThat(lessonTitled(details, "L3").getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
            assertThat(lessonTitled(details, "L4").getChange().getState()).isEqualTo(ContentChangeState.NEW);
            assertThat(details.getFinalQuiz().getChange().getState()).isEqualTo(ContentChangeState.NEW);
            assertThat(details.getModules().getFirst().getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(details.getModules().getLast().getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
        }

        @Test
        void aStudentWhoJoinedAfterAllOfThemSeesAPlainCourse() {
            var course = publishedCourse();

            var first = echoOf(course);
            first.getModules().getFirst().getLessons().getFirst().setDescription("Rewritten body");
            var afterFirst = courseService.updateCourse(instructorUser, course.getId(), first);

            var second = echoOf(afterFirst);
            var lessons = new ArrayList<>(second.getModules().getLast().getLessons());
            lessons.add(lesson("L4"));
            second.getModules().getLast().setLessons(lessons);
            courseService.updateCourse(instructorUser, course.getId(), second);

            User after = lateLearner(course.getId());
            var details = detailsFor(after, course.getId());

            assertThat(details.getCourse().getHasUpdatesSinceEnrollment()).isFalse();
            assertThat(lessonsOf(details))
                    .allSatisfy(l -> assertThat(l.getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED));
            assertThat(details.getModules())
                    .allSatisfy(m -> assertThat(m.getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED));
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("viewers with no enrollment")
    class NotEnrolled {

        @Test
        void aBrowsingVisitorIsToldNothingAboutTheInstructorsEditHistory() {
            var course = publishedCourse();

            var request = echoOf(course);
            request.setTitle("Live course, renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            User visitor = newStudentUser();
            var details = courseService.getCourseDetails(visitor, course.getId(),
                    com.manara.backend.course.dto.CourseViewMode.DISCOVER);

            assertThat(details.getCourse().getHasUpdatesSinceEnrollment()).isFalse();
            assertThat(details.getCourse().getLatestContentUpdateAt()).isNull();
            assertThat(details.getRemovedContent()).isEmpty();
            assertThat(lessonsOf(details))
                    .allSatisfy(l -> assertThat(l.getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED));
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        /**
         * A change landing in the same instant as an enrollment is not a change to that learner.
         *
         * <p>The comparison is strictly {@code isAfter} in both directions — course level and item
         * level — because somebody who joined at the exact microsecond of an edit joined the edited
         * version. Getting this backwards would light the badge for every learner who enrolled
         * during a save.
         */
        @Test
        void aChangeInTheSameInstantAsTheEnrollmentDoesNotCount() {
            var course = publishedCourse();
            courseExistedSince(course.getId(), LONG_BEFORE.minusDays(1));

            User student = newStudentUser();
            var enrollment = enroll(student, course.getId());

            var request = echoOf(course);
            request.setTitle("Renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            // Place the enrollment exactly on the change's own instant.
            LocalDateTime changedAt = reload(course.getId()).getContentUpdatedAt();
            enrolledAt(enrollment.getId(), changedAt);

            assertThat(detailsFor(student, course.getId()).getCourse().getHasUpdatesSinceEnrollment())
                    .isFalse();
        }

        /**
         * A rejected save leaves no trace — not in the timestamps, and not in the change log.
         *
         * <p>The journal writes inside the authoring transaction rather than after it, so a course
         * cannot end up claiming to have been updated by an edit that was refused, and a learner
         * cannot be shown a sentence describing a change that never happened.
         */
        @Test
        void aRejectedEditAnnouncesNothing() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().getLessons().getFirst().setTitle("Would have been renamed");
            // A lesson id from no course at all: validation refuses the payload after it has already
            // walked part of the tree.
            request.getModules().getLast().getLessons().getFirst().setId(999_999L);

            try {
                courseService.updateCourse(instructorUser, course.getId(), request);
            } catch (RuntimeException expected) {
                // The refusal is the setup, not the assertion.
            }

            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isFalse();
            assertThat(detailsFor(student, course.getId()).getRemovedContent()).isEmpty();
            assertThat(lessonsOf(detailsFor(student, course.getId())))
                    .allSatisfy(l -> assertThat(l.getChange().getState())
                            .isEqualTo(ContentChangeState.UNCHANGED));
        }

        /**
         * Unpublishing and republishing does not rewrite anybody's history.
         *
         * <p>Publication decides who can see a course; it is not an edit and must not read as one.
         * A learner who was told about a change before the course was withdrawn is still owed that
         * information when it comes back.
         */
        @Test
        void withdrawingAndRepublishingLeavesTheLearnersAnswerIntact() {
            var course = publishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().getFirst().getLessons().getFirst().setDescription("Rewritten body");
            courseService.updateCourse(instructorUser, course.getId(), request);

            courseService.unpublish(instructorUser, course.getId());
            courseService.publish(instructorUser, course.getId());

            // Re-publishing settles the instructor's own badge and deliberately nothing else.
            assertThat(reload(course.getId()).hasUpdatesSincePublish()).isFalse();
            assertThat(cardFor(student, course.getId()).getHasUpdatesSinceEnrollment()).isTrue();
            assertThat(lessonTitled(detailsFor(student, course.getId()), "L1").getChange().getState())
                    .isEqualTo(ContentChangeState.UPDATED);
        }

        @Test
        void aFlatCourseWithNoModulesIsHandledLikeAnyOther() {
            var course = courseService.createCourse(instructorUser,
                    CourseAuthoringFixtures.flatCourse("Flat course", CourseStatus.PUBLISHED,
                            lesson("F1"), lesson("F2")));
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            var lessons = new ArrayList<>(request.getLessons());
            lessons.add(lesson("F3"));
            request.setLessons(lessons);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var details = detailsFor(student, course.getId());
            assertThat(details.getModules()).isEmpty();
            assertThat(details.getLessons()).extracting(l -> l.getTitle() + ":" + l.getChange().getState())
                    .containsExactly("F1:UNCHANGED", "F2:UNCHANGED", "F3:NEW");
        }
    }

    // --- fixtures that need a quiz attached ----------------------------------

    private static com.manara.backend.lesson.dto.LessonRequest withQuiz(
            com.manara.backend.lesson.dto.LessonRequest lesson,
            com.manara.backend.quiz.dto.QuizRequest quiz) {
        lesson.setQuiz(quiz);
        return lesson;
    }

    private static com.manara.backend.course.dto.ModuleRequest withExam(
            com.manara.backend.course.dto.ModuleRequest module,
            com.manara.backend.quiz.dto.QuizRequest quiz) {
        module.setQuiz(quiz);
        return module;
    }
}
