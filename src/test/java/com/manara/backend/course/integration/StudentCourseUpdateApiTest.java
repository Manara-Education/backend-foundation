package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The update contract as it is actually spoken over HTTP.
 *
 * <p>Everything below the controller is covered by {@link StudentCourseUpdateTest}. What only a
 * request can demonstrate is the three things that live at the boundary and nowhere else:
 *
 * <ul>
 *   <li>that the nested {@code change} objects survive serialisation — a client reads
 *       {@code modules[].lessons[].change.state}, and nothing below this layer proves that path
 *       exists in the JSON rather than only in the DTO;
 *   <li>that the wording is localised from {@code Accept-Language}, so an Arabic reader and an
 *       English one get the same decision in their own words;
 *   <li>that the answer is the <em>authenticated</em> learner's. Nothing in the request names a
 *       student, and the two learners below prove the server is resolving it rather than being
 *       told.
 * </ul>
 */
class StudentCourseUpdateApiTest extends AbstractCourseAuthoringTest {

    private static final String DETAILS = "/api/v1/student/courses/{id}";
    private static final LocalDateTime LONG_BEFORE = LocalDateTime.now().minusDays(30);
    private static final LocalDateTime LONG_AFTER = LocalDateTime.now().plusDays(30);

    @Autowired WebApplicationContext context;

    private MockMvc mockMvc;

    /**
     * Built by hand rather than with {@code @AutoConfigureMockMvc}, following the sibling API test:
     * Spring Boot 4 moved that annotation into {@code spring-boot-webmvc-test}. {@code springSecurity()}
     * installs the real filter chain, so these requests authenticate as a browser's would.
     */
    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private InstructorCourseResponse publishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Over the wire", CourseStatus.PUBLISHED,
                        module("One", lesson("L1"), lesson("L2"))));
    }

    /** A learner of a course that was already live, who has since had a lesson added under them. */
    private User learnerWhoMissedTheChange(Long courseId) {
        courseExistedSince(courseId, LONG_BEFORE.minusDays(1));
        User student = newStudentUser();
        enrolledAt(enroll(student, courseId).getId(), LONG_BEFORE);
        return student;
    }

    private void addALessonAndEditAnother(InstructorCourseResponse course) {
        var request = echoOf(course);
        request.getModules().getFirst().getLessons().getFirst().setDescription("Rewritten body");
        var lessons = new ArrayList<>(request.getModules().getFirst().getLessons());
        lessons.add(lesson("L3"));
        request.getModules().getFirst().setLessons(lessons);
        courseService.updateCourse(instructorUser, course.getId(), request);
    }

    @Test
    @DisplayName("the curriculum carries a per-row verdict all the way into the JSON")
    void perRowVerdictsSerialize() throws Exception {
        var course = publishedCourse();
        User student = learnerWhoMissedTheChange(course.getId());
        addALessonAndEditAnother(course);

        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "ENROLLED").with(user(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.hasUpdatesSinceEnrollment", is(true)))
                .andExpect(jsonPath("$.data.course.latestContentUpdateAt").isNotEmpty())
                // The exact path a client reads. Only a serialised response proves it exists.
                .andExpect(jsonPath("$.data.modules[0].lessons[0].change.state", is("UPDATED")))
                .andExpect(jsonPath("$.data.modules[0].lessons[1].change.state", is("UNCHANGED")))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.state", is("NEW")))
                .andExpect(jsonPath("$.data.modules[0].change.state", is("UNCHANGED")));
    }

    @Test
    @DisplayName("the wording follows Accept-Language, so both readers get the same decision")
    void wordingIsLocalised() throws Exception {
        var course = publishedCourse();
        User student = learnerWhoMissedTheChange(course.getId());
        addALessonAndEditAnother(course);

        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "ENROLLED")
                        .header("Accept-Language", "en").with(user(student)))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.summary", is("New lesson added")))
                .andExpect(jsonPath("$.data.modules[0].lessons[0].change.summary",
                        is("Lesson content updated")));

        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "ENROLLED")
                        .header("Accept-Language", "ar").with(user(student)))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.state", is("NEW")))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.summary",
                        is("تمت إضافة درس جديد")))
                .andExpect(jsonPath("$.data.modules[0].lessons[0].change.summary",
                        is("تم تحديث محتوى الدرس")));
    }

    @Test
    @DisplayName("the answer belongs to the authenticated learner, not to the course")
    void theAnswerIsPerViewer() throws Exception {
        var course = publishedCourse();
        User before = learnerWhoMissedTheChange(course.getId());
        addALessonAndEditAnother(course);

        User after = newStudentUser();
        enrolledAt(enroll(after, course.getId()).getId(), LONG_AFTER);

        // Same URL, same course, no student named anywhere in either request.
        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "ENROLLED").with(user(before)))
                .andExpect(jsonPath("$.data.course.hasUpdatesSinceEnrollment", is(true)))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.state", is("NEW")));

        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "ENROLLED").with(user(after)))
                .andExpect(jsonPath("$.data.course.hasUpdatesSinceEnrollment", is(false)))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.state", is("UNCHANGED")))
                .andExpect(jsonPath("$.data.modules[0].lessons[2].change.summary", is(emptyOrNullString())));
    }

    @Test
    @DisplayName("removed content is listed at course level, where a client can find it")
    void removedContentSerializes() throws Exception {
        var course = publishedCourse();
        User student = learnerWhoMissedTheChange(course.getId());

        var request = echoOf(course);
        request.getModules().getFirst().setLessons(
                List.of(request.getModules().getFirst().getLessons().getFirst()));
        courseService.updateCourse(instructorUser, course.getId(), request);

        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "ENROLLED")
                        .header("Accept-Language", "en").with(user(student)))
                .andExpect(jsonPath("$.data.removedContent[0].entityType", is("LESSON")))
                .andExpect(jsonPath("$.data.removedContent[0].title", is("L2")))
                .andExpect(jsonPath("$.data.removedContent[0].summary", is("Lesson removed")));
    }

    @Test
    @DisplayName("a visitor browsing the catalogue is told nothing about the instructor's edits")
    void discoveryLeaksNoUpdateHistory() throws Exception {
        var course = publishedCourse();
        learnerWhoMissedTheChange(course.getId());
        addALessonAndEditAnother(course);

        User visitor = newStudentUser();

        mockMvc.perform(get(DETAILS, course.getId()).param("mode", "DISCOVER").with(user(visitor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.hasUpdatesSinceEnrollment", is(false)))
                .andExpect(jsonPath("$.data.course.latestContentUpdateAt", is(emptyOrNullString())))
                .andExpect(jsonPath("$.data.removedContent").isEmpty())
                .andExpect(jsonPath("$.data.modules[0].lessons[0].change.state", is("UNCHANGED")));
    }
}
