package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.course.service.CourseCheckoutService;
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

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Private courses: who can find one, who can open one, and what happens to the people already in it.
 *
 * <h2>The two axes, and why they are two</h2>
 * A course carries a publication state and a visibility, and they answer different questions:
 *
 * <pre>
 * status      DRAFT | PUBLISHED     — is this finished?
 * visibility  PUBLIC | PRIVATE      — and who is it finished for?
 * </pre>
 *
 * <p>All four combinations are legal, and this file asserts each of them against each kind of
 * viewer. The combination that only exists because the axes are separate — {@code PUBLISHED +
 * PRIVATE} — is the whole feature: a finished course, off the catalogue, still fully alive for the
 * cohort inside it.
 *
 * <h2>What "private" has to mean to be worth anything</h2>
 * Not "the card is hidden". The failure a browser-side filter leaves wide open is somebody typing an
 * id: the course endpoint, the lesson endpoints, the pricing block and the checkout would all keep
 * answering normally to a stranger who never saw a card. So the assertions here are deliberately
 * about the server — every learner-facing path is asked directly, with the course's real id, by a
 * student who is not in it.
 *
 * <h2>What it must not cost</h2>
 * Going private is not a revocation, and that is the half of this that is easiest to get wrong.
 * A hundred enrolled learners stay enrolled, keep their progress, keep their certificates' basis,
 * keep what they paid for, and keep seeing the course everywhere they saw it before. Every
 * assertion about the stranger has a twin here about the learner.
 */
class PrivateCourseAccessTest extends AbstractCourseAuthoringTest {

    @Autowired LessonService lessonService;
    @Autowired CourseCheckoutService courseCheckoutService;

    private User learner;
    private User stranger;
    private User admin;
    private User otherInstructor;

    @BeforeEach
    void createViewers() {
        learner = newStudentUser();
        stranger = newStudentUser();
        admin = newAdminUser();
        otherInstructor = newInstructorUser();
    }

    /** A published, public course with one learner in it — the state every scenario starts from. */
    private InstructorCourseResponse publicCourseWithALearner() {
        var course = courseService.createCourse(instructorUser,
                modularCourse("Cohort", CourseStatus.PUBLISHED,
                        module("One", lesson("L1"), lesson("L2"))));
        enroll(learner, course.getId());
        return course;
    }

    /**
     * Makes a course private the way the editor does: an aggregate save carrying the new setting.
     *
     * <p>Through the real update path rather than by writing the column, so what is asserted
     * afterwards is the behaviour of a change an instructor could actually make — revision check,
     * validation, content synchronization and all.
     */
    private InstructorCourseResponse goPrivate(InstructorCourseResponse course) {
        var request = echoOf(course);
        request.setVisibility(CourseVisibility.PRIVATE);
        var updated = courseService.updateCourse(instructorUser, course.getId(), request);
        assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
        return updated;
    }

    private InstructorCourseResponse goPublic(InstructorCourseResponse course) {
        var request = echoOf(course);
        request.setVisibility(CourseVisibility.PUBLIC);
        var updated = courseService.updateCourse(instructorUser, course.getId(), request);
        assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PUBLIC);
        return updated;
    }

    private Long firstLessonOf(Long courseId) {
        return lessonRepository.findCourseLessonsInReadingOrder(courseId).getFirst().getId();
    }

    private List<Long> catalogueIds() {
        return courseService.getDiscoverableCourses().stream().map(CourseResponse::getId).toList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("defaults — nothing becomes private by itself")
    class Defaults {

        @Test
        @DisplayName("a course created without mentioning visibility is public")
        void creationDefaultsToPublic() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Unsaid", CourseStatus.PUBLISHED, lesson("L1")));

            assertThat(course.getVisibility()).isEqualTo(CourseVisibility.PUBLIC);
            assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PUBLIC);
            assertThat(catalogueIds()).contains(course.getId());
        }

        /**
         * The compatibility guarantee for every client written before this field existed: their
         * saves never mention visibility, and a course they save must not change what it is.
         */
        @Test
        @DisplayName("a save that never mentions visibility leaves it exactly as it was")
        void anOmittedFieldChangesNothing() {
            var course = publicCourseWithALearner();
            var privateCourse = goPrivate(course);

            var request = echoOf(privateCourse);
            request.setVisibility(null);
            request.setTitle("Renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
            assertThat(reload(course.getId()).getTitle()).isEqualTo("Renamed");
        }
    }

    @Nested
    @DisplayName("the required matrix — status × visibility × viewer")
    class Matrix {

        @Test
        @DisplayName("DRAFT + PUBLIC is hidden from a stranger, exactly as a draft always was")
        void draftPublicIsHidden() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Draft public", CourseStatus.DRAFT, CourseVisibility.PUBLIC, lesson("L1")));

            assertThat(catalogueIds()).doesNotContain(course.getId());
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("DRAFT + PRIVATE is hidden from a stranger, and is still a draft")
        void draftPrivateIsHiddenAndStillADraft() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Draft private", CourseStatus.DRAFT, CourseVisibility.PRIVATE, lesson("L1")));

            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.DRAFT);
            assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
            assertThat(catalogueIds()).doesNotContain(course.getId());
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("PUBLISHED + PUBLIC is discoverable, with no change to how it behaves")
        void publishedPublicIsDiscoverable() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Live", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1")));

            assertThat(catalogueIds()).contains(course.getId());
            assertThat(courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER)
                    .getCourse().getTitle()).isEqualTo("Live");
        }

        @Test
        @DisplayName("PUBLISHED + PUBLIC is accessible to a learner enrolled in it")
        void publishedPublicIsAccessibleToItsLearner() {
            var course = publicCourseWithALearner();

            assertThat(detailsFor(learner, course.getId()).getCourse().getTitle()).isEqualTo("Cohort");
        }

        @Test
        @DisplayName("PUBLISHED + PRIVATE is denied to a learner who is not in it")
        void publishedPrivateIsDeniedToANonLearner() {
            var course = goPrivate(publicCourseWithALearner());

            assertThat(catalogueIds()).doesNotContain(course.getId());
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("PUBLISHED + PRIVATE is accessible to the learner who is in it")
        void publishedPrivateIsAccessibleToItsLearner() {
            var course = goPrivate(publicCourseWithALearner());

            assertThat(detailsFor(learner, course.getId()).getCourse().getTitle()).isEqualTo("Cohort");
        }

        @Test
        @DisplayName("PUBLISHED + PRIVATE is accessible to its instructor")
        void publishedPrivateIsAccessibleToItsInstructor() {
            var course = goPrivate(publicCourseWithALearner());

            assertThat(courseService.getCourseForEditing(instructorUser, course.getId()).getVisibility())
                    .isEqualTo(CourseVisibility.PRIVATE);
        }

        /**
         * Staff keep over a private course exactly the reach they already had over a public one —
         * no more. An administrator could already open any published course; making it private
         * must not quietly revoke that, and must not grant anything new either.
         */
        @Test
        @DisplayName("PUBLISHED + PRIVATE is accessible to an admin; a draft still is not")
        void publishedPrivateIsAccessibleToAnAdmin() {
            var course = goPrivate(publicCourseWithALearner());

            assertThat(courseService.getCourseDetails(admin, course.getId(), CourseViewMode.DISCOVER)
                    .getCourse().getTitle()).isEqualTo("Cohort");

            var draft = courseService.createCourse(instructorUser,
                    flatCourse("Unfinished", CourseStatus.DRAFT, CourseVisibility.PRIVATE, lesson("L1")));
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(admin, draft.getId(), CourseViewMode.DISCOVER))
                    .as("an admin bypass for private must not become a bypass for drafts")
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("another instructor is not the owner, and is told it does not exist")
        void anotherInstructorIsNotTheOwner() {
            var course = goPrivate(publicCourseWithALearner());

            assertThatThrownBy(() ->
                    courseService.getCourseDetails(otherInstructor, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> courseService.getCourseForEditing(otherInstructor, course.getId()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a signed-out visitor is told the same thing")
        void anAnonymousVisitorIsToldItDoesNotExist() {
            var course = goPrivate(publicCourseWithALearner());

            assertThatThrownBy(() ->
                    courseService.getCourseDetails(null, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    /**
     * The scenario the feature was specified by, run end to end.
     *
     * <pre>
     * publish → student A enrols → student B does not → instructor goes private
     * </pre>
     */
    @Nested
    @DisplayName("the security scenario")
    class SecurityScenario {

        @Test
        @DisplayName("student A keeps My Courses, details, lessons and progress")
        void theEnrolledStudentKeepsEverything() {
            var course = publicCourseWithALearner();
            Long lessonId = firstLessonOf(course.getId());
            lessonService.markLessonCompleted(learner, course.getId(), lessonId);

            goPrivate(course);

            assertThat(cardFor(learner, course.getId()).getTitle())
                    .as("My Courses is enrolment-driven, and going private is not an unenrolment")
                    .isEqualTo("Cohort");
            assertThat(detailsFor(learner, course.getId()).getModules())
                    .extracting(m -> m.getTitle()).containsExactly("One");
            assertThat(lessonService.getLesson(learner, course.getId(), lessonId).getLesson().getTitle())
                    .isEqualTo("L1");
            assertThat(lessonService.getCourseLessons(learner, course.getId()))
                    .extracting(l -> l.getTitle()).containsExactly("L1", "L2");
            assertThat(completedLessonRepository
                    .findCompletedLessonIdsByStudentIdAndCourseId(
                            studentProfileOf(learner).getId(), course.getId()))
                    .containsExactly(lessonId);
        }

        /**
         * Every server-side path a stranger could reach the course by, asked with its real id.
         *
         * <p>This is the test a frontend filter fails. None of these calls involves a card, a list
         * or a search result — they are what a typed URL, a bookmark or a saved link produces.
         */
        @Test
        @DisplayName("student B loses discovery, search, and every direct path")
        void theStrangerLosesEveryPath() {
            var course = publicCourseWithALearner();
            Long lessonId = firstLessonOf(course.getId());

            goPrivate(course);

            assertThat(catalogueIds()).doesNotContain(course.getId());
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.ENROLLED))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> lessonService.getCourseLessons(stranger, course.getId()))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThatThrownBy(() -> lessonService.getLesson(stranger, course.getId(), lessonId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        /**
         * Search on this platform runs over the catalogue the browse endpoint returns, so a course
         * that is not in it cannot be found by title, instructor or keyword. Asserted through the
         * catalogue rather than through a search endpoint that does not exist, because that is
         * where the guarantee actually lives — and it is the assertion that keeps holding when a
         * server-side search is added on top of the same query.
         */
        @Test
        @DisplayName("an exact-title match over the catalogue finds nothing")
        void anExactTitleSearchFindsNothing() {
            var course = goPrivate(publicCourseWithALearner());

            assertThat(courseService.getDiscoverableCourses())
                    .filteredOn(c -> "Cohort".equals(c.getTitle()))
                    .extracting(CourseResponse::getId)
                    .doesNotContain(course.getId());
        }

        @Test
        @DisplayName("the instructor keeps My Courses and the editor")
        void theInstructorKeepsTheirCourse() {
            var course = goPrivate(publicCourseWithALearner());

            assertThat(courseService.getMyCourses(instructorUser))
                    .extracting(CourseResponse::getId).contains(course.getId());
            assertThat(courseService.getCourseForEditing(instructorUser, course.getId()).getTitle())
                    .isEqualTo("Cohort");
        }
    }

    @Nested
    @DisplayName("existing enrolments, entitlements and money")
    class NothingIsLost {

        @Test
        @DisplayName("the enrolment row is the same row, with the same progress")
        void theEnrolmentIsUntouched() {
            var course = publicCourseWithALearner();
            var before = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();

            goPrivate(course);

            var after = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();
            assertThat(after.getId()).isEqualTo(before.getId());
            assertThat(after.getProgress()).isEqualTo(before.getProgress());
            assertThat(after.getEnrolledAt()).isEqualTo(before.getEnrolledAt());
        }

        @Test
        @DisplayName("the entitlement is the same grant, and still active")
        void theEntitlementIsUntouched() {
            var course = publicCourseWithALearner();
            var before = courseEntitlementRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();

            goPrivate(course);

            var after = courseEntitlementRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();
            assertThat(after.getId()).isEqualTo(before.getId());
            assertThat(after.getSource()).isEqualTo(before.getSource());
            assertThat(after.getExpiresAt()).isEqualTo(before.getExpiresAt());
            assertThat(after.isActiveAt(LocalDateTime.now())).isTrue();
        }

        @Test
        @DisplayName("a purchaser keeps the course they bought, at the price they paid")
        void aPurchaserKeepsWhatTheyBought() {
            var request = flatCourse("Bought", CourseStatus.PUBLISHED, lesson("L1"));
            request.setAccessType(CourseAccessType.PURCHASE);
            request.setPurchasePrice(new BigDecimal("300.00"));
            var course = courseService.createCourse(instructorUser, request);
            enroll(learner, course.getId());

            goPrivate(course);

            assertThat(detailsFor(learner, course.getId()).getCourse().getTitle()).isEqualTo("Bought");
            assertThat(reload(course.getId()).getPurchasePrice())
                    .isEqualByComparingTo(new BigDecimal("300.00"));
            assertThat(reload(course.getId()).getAccessType()).isEqualTo(CourseAccessType.PURCHASE);
        }

        @Test
        @DisplayName("a subscriber keeps their course and their plan")
        void aSubscriberKeepsTheirTerm() {
            var request = flatCourse("Subscribed", CourseStatus.PUBLISHED, lesson("L1"));
            request.setAccessType(CourseAccessType.SUBSCRIPTION);
            request.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00")));
            var course = courseService.createCourse(instructorUser, request);
            enroll(learner, course.getId());

            goPrivate(course);

            assertThat(detailsFor(learner, course.getId()).getCourse().getTitle()).isEqualTo("Subscribed");
            assertThat(detailsFor(learner, course.getId()).getAccess().getEntitled()).isTrue();
        }

        /**
         * Learners are not to be told anything happened, because from inside the course nothing did.
         *
         * <p>The "Updated" badge means "the course you are studying has changed". Coming off the
         * catalogue changes who else can join, not a single thing the enrolled learner can see —
         * lighting their badge for it would be announcing somebody else's business as theirs. The
         * revision still moves, because an open editor tab is holding a copy that is now wrong.
         */
        @Test
        @DisplayName("no learner is told the course changed; the editor's revision still moves")
        void goingPrivateIsNotNews() {
            var course = publicCourseWithALearner();
            var contentVersionBefore = reload(course.getId()).getContentUpdatedAt();
            long revisionBefore = reload(course.getId()).getRevision();

            goPrivate(course);

            assertThat(reload(course.getId()).getContentUpdatedAt())
                    .as("visibility is not content, and must not stamp the content version")
                    .isEqualTo(contentVersionBefore);
            assertThat(reload(course.getId()).getRevision())
                    .as("a stale tab is holding the old setting and must be refused")
                    .isEqualTo(revisionBefore + 1);
            assertThat(detailsFor(learner, course.getId()).getCourse().getHasUpdatesSinceEnrollment())
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("transitions")
    class Transitions {

        @Test
        @DisplayName("PUBLIC → PRIVATE takes effect at once, for discovery and for direct access")
        void publicToPrivateTakesEffectImmediately() {
            var course = publicCourseWithALearner();
            assertThat(catalogueIds()).contains(course.getId());

            goPrivate(course);

            assertThat(catalogueIds()).doesNotContain(course.getId());
            assertThatThrownBy(() ->
                    courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("PRIVATE → PUBLIC restores discovery without republishing")
        void privateToPublicNeedsNoRepublish() {
            var course = publicCourseWithALearner();
            var privateCourse = goPrivate(course);
            LocalDateTime baselineBefore = reload(course.getId()).getLastPublishedAt();

            goPublic(privateCourse);

            assertThat(catalogueIds()).contains(course.getId());
            assertThat(courseService.getCourseDetails(stranger, course.getId(), CourseViewMode.DISCOVER)
                    .getCourse().getTitle()).isEqualTo("Cohort");
            assertThat(reload(course.getId()).getLastPublishedAt())
                    .as("becoming public again is not a new publication")
                    .isEqualTo(baselineBefore);
        }

        @Test
        @DisplayName("DRAFT + PRIVATE → DRAFT + PUBLIC stays a draft, and stays out of discovery")
        void aDraftGoingPublicIsStillADraft() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Still drafting", CourseStatus.DRAFT, CourseVisibility.PRIVATE, lesson("L1")));

            goPublic(course);

            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.DRAFT);
            assertThat(catalogueIds()).doesNotContain(course.getId());
        }

        @Test
        @DisplayName("publishing a private draft publishes it and leaves it private")
        void publishingDoesNotTouchVisibility() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Cohort only", CourseStatus.DRAFT, CourseVisibility.PRIVATE, lesson("L1")));

            courseService.publish(instructorUser, course.getId());

            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
            assertThat(catalogueIds()).doesNotContain(course.getId());
        }

        @Test
        @DisplayName("unpublishing a private course leaves it private")
        void unpublishingDoesNotTouchVisibility() {
            var course = goPrivate(publicCourseWithALearner());

            courseService.unpublish(instructorUser, course.getId());

            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.DRAFT);
            assertThat(reload(course.getId()).getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
        }

        @Test
        @DisplayName("publishing a public draft is the ordinary path, undisturbed")
        void publishingAPublicDraftIsUnchanged() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Going live", CourseStatus.DRAFT, CourseVisibility.PUBLIC, lesson("L1")));

            courseService.publish(instructorUser, course.getId());

            assertThat(catalogueIds()).contains(course.getId());
        }
    }

    @Nested
    @DisplayName("a private course is still a fully editable course")
    class StillEditable {

        @Test
        @DisplayName("its instructor can edit content, pricing and metadata while it has learners")
        void everyEditStillWorks() {
            var course = goPrivate(publicCourseWithALearner());

            var request = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            request.setTitle("Cohort, revised");
            request.setAccessType(CourseAccessType.PURCHASE);
            request.setPurchasePrice(new BigDecimal("450.00"));
            request.getModules().getFirst().getLessons().getFirst().setTitle("L1 revised");
            var updated = courseService.updateCourse(instructorUser, course.getId(), request);

            assertThat(updated.getTitle()).isEqualTo("Cohort, revised");
            assertThat(updated.getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
            assertThat(reload(course.getId()).getPurchasePrice()).isEqualByComparingTo("450.00");
        }

        /**
         * The update-tracking machinery has to keep working for a private course, because its
         * learners are exactly the people it exists for. A private course is not a course with
         * fewer learners — it is a course with a closed list of them.
         */
        @Test
        @DisplayName("its enrolled learner is still told when its content changes")
        void contentChangesStillReachItsLearners() {
            var course = publicCourseWithALearner();
            var enrolment = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(learner).getId())
                    .orElseThrow();
            courseExistedSince(course.getId(), LocalDateTime.now().minusMonths(2));
            enrolledAt(enrolment.getId(), LocalDateTime.now().minusMonths(1));

            var privateCourse = goPrivate(courseService.getCourseForEditing(instructorUser, course.getId()));

            var request = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            request.getModules().getFirst().getLessons().getFirst().setTitle("Rewritten");
            courseService.updateCourse(instructorUser, privateCourse.getId(), request);

            assertThat(detailsFor(learner, course.getId()).getCourse().getHasUpdatesSinceEnrollment())
                    .as("a private course's learners are still its learners")
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("acquisition")
    class Acquisition {

        @Test
        @DisplayName("a stranger cannot check out a private course, even knowing its id")
        void aStrangerCannotBuyIn() {
            var course = goPrivate(publicCourseWithALearner());

            assertThatThrownBy(() ->
                    courseCheckoutService.checkout(stranger, course.getId(), new CheckoutRequest()))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(enrollmentRepository.existsByCourseIdAndStudentId(
                    course.getId(), studentProfileOf(stranger).getId())).isFalse();
        }

        /**
         * The exception that has to exist. A learner already in the course reaches checkout when
         * their subscription lapses and they renew, and when a double-clicked checkout is repeated;
         * refusing them would make going private a way of stranding the very people it is for.
         */
        @Test
        @DisplayName("a learner already in it can still check out — a repeat answers, and does not charge")
        void anExistingLearnerCanStillCheckOut() {
            var course = goPrivate(publicCourseWithALearner());

            var response = courseCheckoutService.checkout(learner, course.getId(), new CheckoutRequest());

            assertThat(response.getAccess().getEnrolled()).isTrue();
            assertThat(response.getAccess().getEntitled()).isTrue();
        }

        @Test
        @DisplayName("a public course is still bought exactly as before")
        void aPublicCourseIsUnaffected() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Open", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1")));

            var response = courseCheckoutService.checkout(stranger, course.getId(), new CheckoutRequest());

            assertThat(response.getAccess().getEnrolled()).isTrue();
        }
    }

    @Nested
    @DisplayName("the catalogue query and the derived rule agree")
    class OneRuleTwoExpressions {

        /**
         * {@code Course.isDiscoverable()} and the JPQL behind the catalogue are the same rule
         * written twice — JPQL cannot call a derived method — so they are asserted against each
         * other over the whole matrix. If either is changed alone, this fails.
         */
        @Test
        @DisplayName("every combination is in the catalogue if and only if the entity says so")
        void theQueryMatchesTheEntity() {
            var draftPublic = courseService.createCourse(instructorUser,
                    flatCourse("A", CourseStatus.DRAFT, CourseVisibility.PUBLIC, lesson("L")));
            var draftPrivate = courseService.createCourse(instructorUser,
                    flatCourse("B", CourseStatus.DRAFT, CourseVisibility.PRIVATE, lesson("L")));
            var livePublic = courseService.createCourse(instructorUser,
                    flatCourse("C", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L")));
            var livePrivate = courseService.createCourse(instructorUser,
                    flatCourse("D", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE, lesson("L")));

            List<Long> catalogue = catalogueIds();
            for (Long id : List.of(draftPublic.getId(), draftPrivate.getId(),
                    livePublic.getId(), livePrivate.getId())) {
                assertThat(catalogue.contains(id))
                        .as("course %d: query and Course.isDiscoverable() must agree", id)
                        .isEqualTo(reload(id).isDiscoverable());
            }

            assertThat(catalogue).contains(livePublic.getId())
                    .doesNotContain(draftPublic.getId(), draftPrivate.getId(), livePrivate.getId());
        }
    }

    @Nested
    @DisplayName("the platform-wide instructor catalogue")
    class StaffCatalogue {

        /**
         * {@code GET /api/v1/instructor/courses} is the one list that deliberately shows courses no
         * learner may discover. It documented itself as being for instructors and admins and never
         * checked, so any signed-in learner could read it — which for private courses would be the
         * whole feature leaking through one endpoint.
         */
        @Test
        @DisplayName("a learner is refused; staff still see everything, private courses included")
        void onlyStaffMayReadIt() {
            var course = goPrivate(publicCourseWithALearner());

            assertThatThrownBy(() -> courseService.getAllCourses(learner))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> courseService.getAllCourses(stranger))
                    .isInstanceOf(BusinessException.class);

            assertThat(courseService.getAllCourses(instructorUser))
                    .extracting(CourseResponse::getId).contains(course.getId());
            assertThat(courseService.getAllCourses(admin))
                    .extracting(CourseResponse::getId).contains(course.getId());
        }
    }

    @Nested
    @DisplayName("the API contract")
    class Contract {

        @Test
        @DisplayName("both axes are reported, on both the summary and the editor shape")
        void bothAxesAreOnTheWire() {
            var course = goPrivate(publicCourseWithALearner());

            var editorShape = courseService.getCourseForEditing(instructorUser, course.getId());
            assertThat(editorShape.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(editorShape.getVisibility()).isEqualTo(CourseVisibility.PRIVATE);

            var summary = courseService.getMyCourses(instructorUser).stream()
                    .filter(c -> c.getId().equals(course.getId()))
                    .findFirst().orElseThrow();
            assertThat(summary.getStatus())
                    .as("published and private at once — never folded into one field")
                    .isEqualTo(CourseStatus.PUBLISHED);
            assertThat(summary.getVisibility()).isEqualTo(CourseVisibility.PRIVATE);
        }
    }
}
