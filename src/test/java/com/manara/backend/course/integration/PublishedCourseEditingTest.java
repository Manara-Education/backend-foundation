package com.manara.backend.course.integration;

import com.manara.backend.common.json.Patch;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A published course is editable by its instructor, and stays published while it is edited.
 *
 * <p>This is the rule the work these tests belong to exists to establish. Publication decides who
 * can see a course; it is not a lock on its author, and the only things it does gate are the
 * handful of invariants a course genuinely has to satisfy to be live.
 */
class PublishedCourseEditingTest extends AbstractCourseAuthoringTest {

    @Test
    @DisplayName("an instructor can rename a published course, and it stays published")
    void publishedCourseRemainsEditable() {
        var course = courseService.createCourse(instructorUser,
                modularCourse("Arabic Basics", CourseStatus.PUBLISHED, module("Intro", lesson("L1"))));

        var request = echoOf(course);
        request.setTitle("Arabic Basics, Revised");
        var updated = courseService.updateCourse(instructorUser, course.getId(), request);

        assertThat(updated.getTitle()).isEqualTo("Arabic Basics, Revised");
        assertThat(updated.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(updated.getHasUpdatesSincePublish()).isTrue();

        var reloaded = reload(course.getId());
        assertThat(reloaded.getTitle()).isEqualTo("Arabic Basics, Revised");
        assertThat(reloaded.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
    }

    @Test
    @DisplayName("a draft course is editable too, and stays a draft")
    void draftCourseRemainsEditable() {
        var course = courseService.createCourse(instructorUser,
                modularCourse("Draft Course", CourseStatus.DRAFT, module("Intro", lesson("L1"))));

        var request = echoOf(course);
        request.setTitle("Draft Course, Revised");
        var updated = courseService.updateCourse(instructorUser, course.getId(), request);

        assertThat(updated.getTitle()).isEqualTo("Draft Course, Revised");
        assertThat(updated.getStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(updated.getHasUpdatesSincePublish()).isFalse();
    }

    @Test
    @DisplayName("a duration of zero — what a course with unmeasured videos has — round-trips")
    void aCourseWhoseVideosHaveNoMeasuredDurationCanStillBeSaved() {
        // The regression this closes: every lesson starts at duration 0 and stays there until an
        // out-of-band provider lookup lands, so the aggregate the editor loads carries duration 0.
        // Echoing that back was answered "duration must be positive", which made the course
        // permanently uneditable through its own editor.
        var course = courseService.createCourse(instructorUser,
                modularCourse("Unmeasured", CourseStatus.PUBLISHED, module("Intro", lesson("L1"))));
        assertThat(course.getDuration()).isZero();

        var request = echoOf(course);
        request.setDuration(course.getDuration());
        request.setTitle("Still editable");

        var updated = courseService.updateCourse(instructorUser, course.getId(), request);
        assertThat(updated.getTitle()).isEqualTo("Still editable");
    }

    @Test
    @DisplayName("duration is the server's figure, not the client's")
    void durationIsDerivedNotAccepted() {
        var course = courseService.createCourse(instructorUser,
                modularCourse("Derived", CourseStatus.PUBLISHED, module("Intro", lesson("L1"))));

        var request = echoOf(course);
        request.setDuration(9999);
        courseService.updateCourse(instructorUser, course.getId(), request);

        assertThat(reload(course.getId()).getDuration())
                .as("a client-supplied duration must not overwrite the sum of the lessons")
                .isNotEqualTo(9999);
    }

    @Nested
    @DisplayName("partial update semantics")
    class PartialUpdates {

        @Test
        @DisplayName("a payload that never mentions the cover image leaves it alone")
        void omittedImageIsPreserved() {
            var request = modularCourse("Covered", CourseStatus.PUBLISHED, module("Intro", lesson("L1")));
            request.setImage(Patch.of("/uploads/cover.png"));
            request.setSubtitle(Patch.of("Level 1"));
            var course = courseService.createCourse(instructorUser, request);

            // A metadata-only save that says nothing about the cover or the subtitle.
            var metadataOnly = CourseRequest.builder()
                    .expectedRevision(course.getRevision())
                    .title("Covered, renamed")
                    .description(course.getDescription())
                    .build();
            var updated = courseService.updateCourse(instructorUser, course.getId(), metadataOnly);

            assertThat(updated.getTitle()).isEqualTo("Covered, renamed");
            assertThat(updated.getImage()).isEqualTo("/uploads/cover.png");
            assertThat(updated.getSubtitle()).isEqualTo("Level 1");
        }

        @Test
        @DisplayName("a payload that explicitly sends a null cover image clears it")
        void explicitNullImageClearsIt() {
            var request = modularCourse("Covered", CourseStatus.PUBLISHED, module("Intro", lesson("L1")));
            request.setImage(Patch.of("/uploads/cover.png"));
            var course = courseService.createCourse(instructorUser, request);

            var clearing = CourseRequest.builder()
                    .expectedRevision(course.getRevision())
                    .title(course.getTitle())
                    .description(course.getDescription())
                    .image(null)
                    .build();

            assertThat(courseService.updateCourse(instructorUser, course.getId(), clearing).getImage()).isNull();
        }

        @Test
        @DisplayName("a metadata-only save leaves the whole content tree standing")
        void omittedContentIsPreserved() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Intact", CourseStatus.PUBLISHED,
                            module("One", lesson("L1")), module("Two", lesson("L2"))));

            var metadataOnly = CourseRequest.builder()
                    .expectedRevision(course.getRevision())
                    .title("Intact, renamed")
                    .description(course.getDescription())
                    .build();
            courseService.updateCourse(instructorUser, course.getId(), metadataOnly);

            assertThat(persistedModuleTitles(course.getId())).containsExactly("One", "Two");
            assertThat(lessonRepository.countByCourseId(course.getId())).isEqualTo(2);
        }

        @Test
        @DisplayName("a metadata-only save keeps the price and the access type")
        void omittedPricingIsPreserved() {
            var request = modularCourse("Priced", CourseStatus.PUBLISHED, module("Intro", lesson("L1")));
            request.setAccessType(CourseAccessType.PURCHASE);
            request.setPurchasePrice(new BigDecimal("499.00"));
            var course = courseService.createCourse(instructorUser, request);

            var metadataOnly = CourseRequest.builder()
                    .expectedRevision(course.getRevision())
                    .title("Priced, renamed")
                    .description(course.getDescription())
                    .build();
            var updated = courseService.updateCourse(instructorUser, course.getId(), metadataOnly);

            assertThat(updated.getAccessType()).isEqualTo(CourseAccessType.PURCHASE);
            assertThat(updated.getPurchasePrice()).isEqualByComparingTo("499.00");
        }
    }

    @Nested
    @DisplayName("mass assignment")
    class MassAssignment {

        @Test
        @DisplayName("ownership, enrolment counts and identity are not fields of the update payload")
        void protectedFieldsAreNotAssignable() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Owned", CourseStatus.PUBLISHED, module("Intro", lesson("L1"))));
            var before = reload(course.getId());
            Long ownerId = before.getInstructor().getId();
            Integer students = before.getStudentsCount();

            var request = echoOf(course);
            request.setTitle("Renamed");
            courseService.updateCourse(instructorUser, course.getId(), request);

            var after = reload(course.getId());
            assertThat(after.getId()).isEqualTo(course.getId());
            assertThat(after.getInstructor().getId()).isEqualTo(ownerId);
            assertThat(after.getStudentsCount()).isEqualTo(students);
            assertThat(after.getCreatedAt()).isEqualTo(before.getCreatedAt());
        }
    }

    @Nested
    @DisplayName("publication lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("an ordinary save that says nothing about status leaves a published course published")
        void contentSavesDoNotTouchPublication() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Live", CourseStatus.PUBLISHED, module("Intro", lesson("L1"))));

            var request = echoOf(course);
            assertThat(request.getStatus()).isNull();
            request.setTitle("Live, renamed");

            assertThat(courseService.updateCourse(instructorUser, course.getId(), request).getStatus())
                    .isEqualTo(CourseStatus.PUBLISHED);
            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        }

        @Test
        @DisplayName("publish and unpublish are operations of their own")
        void explicitLifecycleOperations() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Toggling", CourseStatus.DRAFT, module("Intro", lesson("L1"))));

            assertThat(courseService.publish(instructorUser, course.getId()).getStatus())
                    .isEqualTo(CourseStatus.PUBLISHED);
            assertThat(reload(course.getId()).getLastPublishedAt()).isNotNull();

            assertThat(courseService.unpublish(instructorUser, course.getId()).getStatus())
                    .isEqualTo(CourseStatus.DRAFT);
            assertThat(reload(course.getId()).getLastPublishedAt())
                    .as("unpublishing keeps the publication history rather than erasing it")
                    .isNotNull();
        }

        @Test
        @DisplayName("a course with nothing to teach cannot be published")
        void publishingRequiresALesson() {
            var course = courseService.createCourse(instructorUser,
                    CourseRequest.builder()
                            .title("Empty course")
                            .description("Nothing in it yet, which is fine for a draft")
                            .structure(CourseStructure.MODULES)
                            .modules(List.of())
                            .status(CourseStatus.DRAFT)
                            .build());

            assertThatThrownBy(() -> courseService.publish(instructorUser, course.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.publishRequiresLesson");

            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.DRAFT);
        }

        @Test
        @DisplayName("a published course may not edit its way to being empty")
        void aLiveCourseCannotDeleteItsLastLesson() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Live", CourseStatus.PUBLISHED, lesson("Only lesson")));

            var emptying = CourseRequest.builder()
                    .expectedRevision(course.getRevision())
                    .title(course.getTitle())
                    .description(course.getDescription())
                    .structure(CourseStructure.FLAT)
                    .lessons(List.of())
                    .build();

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), emptying))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.publishRequiresLesson");

            assertThat(lessonRepository.countByCourseId(course.getId()))
                    .as("the rejected payload must not have deleted anything")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("a draft may be empty; the completeness rules are publication's, not editing's")
        void aDraftMayBeIncomplete() {
            var course = courseService.createCourse(instructorUser,
                    flatCourse("Work in progress", CourseStatus.DRAFT, lesson("Only lesson")));

            var emptying = CourseRequest.builder()
                    .expectedRevision(course.getRevision())
                    .title(course.getTitle())
                    .description(course.getDescription())
                    .structure(CourseStructure.FLAT)
                    .lessons(List.of())
                    .build();

            courseService.updateCourse(instructorUser, course.getId(), emptying);
            assertThat(lessonRepository.countByCourseId(course.getId())).isZero();
        }
    }
}
