package com.manara.backend.course.integration;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The aggregate {@code PUT} as a client actually sends it: real JSON, through Jackson, over HTTP.
 *
 * <h2>Why this file exists</h2>
 * {@code subtitle} and {@code image} could not be written at all. The API answered {@code 200},
 * echoed the old values back, and wrote nothing — for every instructor, on every course, for as
 * long as the feature had shipped. Neither could be cleared either, so a published course's cover
 * image was permanently whatever it had been on the day it was created.
 *
 * <p>The entire test suite was green throughout, and it could not have been otherwise: every
 * authoring test built a {@code CourseRequest} in Java and called {@code CourseService} directly.
 * The defect was in how Jackson constructs that object from JSON, which is a step no test took.
 * Presence was recorded inside the DTO's setters; Spring Boot 4's Jackson 3 builds the DTO through
 * a constructor instead, so the setters never ran, every optional field looked absent, and both
 * were skipped by the update.
 *
 * <p>So the rule this file follows is: <strong>nothing here calls a service.</strong> Every
 * mutation is a request. Services are used only to build fixtures and to read results back the way
 * a second client would.
 *
 * <p>Each case is checked four ways, because a response that agrees with itself proves nothing: the
 * response body, the row in PostgreSQL, an instructor refetch, and the learner's own view of the
 * course they are enrolled in.
 */
class CourseAggregateHttpContractTest extends AbstractCourseAuthoringTest {

    private static final String BASE = "/api/v1/instructor/courses";

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;
    private User learner;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        learner = newStudentUser();
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    /** A published MODULES course with a cover, a subtitle, and a learner already studying it. */
    private InstructorCourseResponse liveModularCourse() {
        var request = modularCourse("Live modular", CourseStatus.PUBLISHED,
                module("One", lesson("L1")), module("Two", lesson("L2")));
        request.setSubtitle(com.manara.backend.common.json.Patch.of("Original subtitle"));
        request.setImage(com.manara.backend.common.json.Patch.of("/uploads/original.png"));
        return published(courseService.createCourse(instructorUser, request));
    }

    /** The same, flat. Both structures share every rule this file is about, and neither is assumed. */
    private InstructorCourseResponse liveFlatCourse() {
        var request = flatCourse("Live flat", CourseStatus.PUBLISHED, lesson("L1"), lesson("L2"));
        request.setSubtitle(com.manara.backend.common.json.Patch.of("Original subtitle"));
        request.setImage(com.manara.backend.common.json.Patch.of("/uploads/original.png"));
        return published(courseService.createCourse(instructorUser, request));
    }

    private InstructorCourseResponse published(InstructorCourseResponse created) {
        // Backdated and enrolled so the learner joined before the edit under test, which is the
        // situation the whole audit was about: a course that is live and has students on it.
        courseExistedSince(created.getId(), LocalDateTime.now().minusMonths(1));
        var enrollment = enroll(learner, created.getId());
        enrolledAt(enrollment.getId(), LocalDateTime.now().minusMonths(1));
        return courseService.getCourseForEditing(instructorUser, created.getId());
    }

    /** The whole body a client sends, with only the parts under test varying. */
    private String body(InstructorCourseResponse course, String extraFields) {
        return """
                {"title":"%s","description":"%s","expectedRevision":%d%s}
                """.formatted(course.getTitle(), course.getDescription(), course.getRevision(),
                extraFields.isEmpty() ? "" : "," + extraFields);
    }

    private String save(InstructorCourseResponse course, String extraFields) throws Exception {
        return mockMvc.perform(put(BASE + "/{id}", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(course, extraFields)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String storedSubtitle(Long courseId) {
        return jdbcTemplate.queryForObject("SELECT subtitle FROM courses WHERE id = ?", String.class, courseId);
    }

    private String storedImage(Long courseId) {
        return jdbcTemplate.queryForObject("SELECT image FROM courses WHERE id = ?", String.class, courseId);
    }

    // ── The four ways every result is confirmed ─────────────────────────────

    private void assertEverySurfaceShows(Long courseId, String responseBody,
                                         String expectedSubtitle, String expectedImage) {
        assertThat(responseBody).contains(expectedSubtitle == null ? "\"subtitle\":null"
                : "\"subtitle\":\"" + expectedSubtitle + "\"");

        assertThat(storedSubtitle(courseId)).isEqualTo(expectedSubtitle);
        assertThat(storedImage(courseId)).isEqualTo(expectedImage);

        var refetched = courseService.getCourseForEditing(instructorUser, courseId);
        assertThat(refetched.getSubtitle()).isEqualTo(expectedSubtitle);
        assertThat(refetched.getImage()).isEqualTo(expectedImage);

        var asLearnerSees = courseService.getCourseDetails(learner, courseId, CourseViewMode.ENROLLED);
        assertThat(asLearnerSees.getCourse().getSubtitle()).isEqualTo(expectedSubtitle);
        assertThat(asLearnerSees.getCourse().getImage()).isEqualTo(expectedImage);
    }

    @Nested
    @DisplayName("subtitle and image, over the wire")
    class OptionalMetadata {

        @Test
        @DisplayName("a new subtitle is written, and reaches the learner")
        void subtitleIsUpdated() throws Exception {
            var course = liveModularCourse();
            var before = reload(course.getId());

            String response = save(course, "\"subtitle\":\"A new subtitle\"");

            assertEverySurfaceShows(course.getId(), response, "A new subtitle", "/uploads/original.png");

            // A real change to something a learner can see, so the version moves and the badge lights.
            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isAfter(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isTrue();
            assertThat(after.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        }

        @Test
        @DisplayName("an explicit null clears the subtitle")
        void subtitleIsCleared() throws Exception {
            var course = liveModularCourse();

            String response = save(course, "\"subtitle\":null");

            assertEverySurfaceShows(course.getId(), response, null, "/uploads/original.png");
            assertThat(reload(course.getId()).hasUpdatesSincePublish()).isTrue();
        }

        @Test
        @DisplayName("a new cover image is written, and the learner sees it")
        void imageIsUpdated() throws Exception {
            var course = liveModularCourse();

            String response = save(course, "\"image\":\"/uploads/replacement.png\"");

            assertEverySurfaceShows(course.getId(), response, "Original subtitle", "/uploads/replacement.png");
        }

        @Test
        @DisplayName("an explicit null clears the cover image")
        void imageIsCleared() throws Exception {
            var course = liveModularCourse();

            String response = save(course, "\"image\":null");

            assertEverySurfaceShows(course.getId(), response, "Original subtitle", null);
        }

        /**
         * The other half of the contract, and the reason the naive repair would be wrong.
         *
         * <p>Treating every missing key as {@code null} would make this pass a blank subtitle and a
         * blank cover on every metadata-only save — which is the bug that made presence tracking
         * necessary in the first place. Absent has to stay absent.
         */
        @Test
        @DisplayName("a payload that mentions neither leaves both exactly as they were")
        void omittingBothPreservesBoth() throws Exception {
            var course = liveModularCourse();
            var before = reload(course.getId());

            String response = save(course, "");

            assertEverySurfaceShows(course.getId(), response, "Original subtitle", "/uploads/original.png");
            // And nothing changed, so nothing was announced.
            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isFalse();
        }

        @Test
        @DisplayName("naming one of the two does not clear the other")
        void oneFieldDoesNotDisturbTheOther() throws Exception {
            var course = liveModularCourse();

            String response = save(course, "\"subtitle\":\"Only the subtitle moved\"");

            assertEverySurfaceShows(course.getId(), response,
                    "Only the subtitle moved", "/uploads/original.png");
        }

        @Test
        @DisplayName("a FLAT course behaves identically")
        void flatCourseIsTheSame() throws Exception {
            var course = liveFlatCourse();

            String response = save(course, "\"subtitle\":\"Flat subtitle\",\"image\":\"/uploads/flat.png\"");

            assertEverySurfaceShows(course.getId(), response, "Flat subtitle", "/uploads/flat.png");
        }

        @Test
        @DisplayName("a FLAT course clears both the same way")
        void flatCourseClearsBoth() throws Exception {
            var course = liveFlatCourse();

            String response = save(course, "\"subtitle\":null,\"image\":null");

            assertEverySurfaceShows(course.getId(), response, null, null);
        }
    }

    @Nested
    @DisplayName("the revision the save was built from")
    class Revision {

        @Test
        @DisplayName("every read hands the client the revision to quote back")
        void readsCarryTheRevision() throws Exception {
            var course = liveModularCourse();

            assertThat(course.getRevision()).isNotNull();

            String response = save(course, "\"subtitle\":\"Moved on\"");
            assertThat(response).contains("\"revision\":" + (course.getRevision() + 1));
        }

        @Test
        @DisplayName("a save that cannot say what it was built from is refused by name")
        void aSaveWithNoRevisionIsRefused() throws Exception {
            var course = liveModularCourse();
            String noRevision = """
                    {"title":"%s","description":"%s","subtitle":"Should not land"}
                    """.formatted(course.getTitle(), course.getDescription());

            mockMvc.perform(put(BASE + "/{id}", course.getId())
                            .with(user(instructorUser)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(noRevision))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COURSE_REVISION_REQUIRED"));

            assertThat(storedSubtitle(course.getId())).isEqualTo("Original subtitle");
        }

        @Test
        @DisplayName("a stale save is 409 with a code the client can act on, and writes nothing")
        void aStaleSaveIsRefused() throws Exception {
            var course = liveModularCourse();
            var before = reload(course.getId());

            // Somebody else saves, so the revision the first client is holding is no longer current.
            save(course, "\"subtitle\":\"The newer edit\"");

            String stale = """
                    {"title":"Renamed from a stale tab","description":"%s","expectedRevision":%d}
                    """.formatted(course.getDescription(), course.getRevision());

            mockMvc.perform(put(BASE + "/{id}", course.getId())
                            .with(user(instructorUser)).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(stale))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value("error"))
                    .andExpect(jsonPath("$.code").value("COURSE_VERSION_CONFLICT"));

            // A rejected save is not a mutation: not the title, not the newer edit it would have
            // reverted, and not the version signal the learner reads.
            var after = reload(course.getId());
            assertThat(after.getTitle()).isEqualTo("Live modular");
            assertThat(storedSubtitle(course.getId())).isEqualTo("The newer edit");
            assertThat(after.getContentUpdatedAt()).isAfter(before.getContentUpdatedAt());

            var afterRetry = reload(course.getId());
            assertThat(afterRetry.getContentUpdatedAt()).isEqualTo(after.getContentUpdatedAt());
        }
    }
}
