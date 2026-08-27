package com.manara.backend.course.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The contract as it is actually spoken over HTTP.
 *
 * <p>Everything below the controller is covered by the service-level tests. What only a request can
 * demonstrate is bean validation, JSON binding and the routes themselves — and the regression that
 * started this work lived exactly there: {@code @Positive} on {@code duration} rejected the very
 * number the API had just returned, so the payload never reached the service at all.
 */
class CourseAuthoringApiTest extends AbstractCourseAuthoringTest {

    private static final String BASE = "/api/v1/instructor/courses";

    @Autowired WebApplicationContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    /**
     * Built by hand rather than with {@code @AutoConfigureMockMvc}, following the auth integration
     * test: Spring Boot 4 moved that annotation into {@code spring-boot-webmvc-test}, and pulling
     * in a module for one annotation is not worth it. {@code springSecurity()} installs the real
     * filter chain, so these requests pass through CSRF and authentication as a browser's would.
     */
    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private InstructorCourseResponse publishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Over the wire", CourseStatus.PUBLISHED,
                        module("One", lesson("L1")), module("Two", lesson("L2"))));
    }

    @Test
    @DisplayName("a payload echoing the API's own duration of 0 is accepted, not rejected")
    void durationZeroIsAccepted() throws Exception {
        var course = publishedCourse();
        assertThat(course.getDuration()).isZero();

        String body = """
                {"title":"Renamed over the wire","description":"%s","duration":0}
                """.formatted(course.getDescription());

        mockMvc.perform(put(BASE + "/{id}", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Renamed over the wire"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.hasUpdatesSincePublish").value(true));
    }

    @Test
    @DisplayName("a negative duration is still refused")
    void negativeDurationIsRejected() throws Exception {
        var course = publishedCourse();
        String body = """
                {"title":"Nope","description":"%s","duration":-5}
                """.formatted(course.getDescription());

        mockMvc.perform(put(BASE + "/{id}", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a body with no status leaves a published course published")
    void omittingStatusPreservesPublication() throws Exception {
        var course = publishedCourse();
        String body = """
                {"title":"Still live","description":"%s"}
                """.formatted(course.getDescription());

        mockMvc.perform(put(BASE + "/{id}", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);
    }

    @Test
    @DisplayName("fields the payload has no business carrying are ignored, not bound")
    void unknownFieldsAreIgnored() throws Exception {
        var course = publishedCourse();
        Long realOwner = reload(course.getId()).getInstructor().getId();

        String body = """
                {"title":"Mass assignment attempt","description":"%s",
                 "id":123456,"instructorId":999999,"studentsCount":5000,
                 "lastPublishedAt":"2020-01-01T00:00:00","contentUpdatedAt":"2020-01-01T00:00:00"}
                """.formatted(course.getDescription());

        mockMvc.perform(put(BASE + "/{id}", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(course.getId()));

        var reloaded = reload(course.getId());
        assertThat(reloaded.getInstructor().getId()).isEqualTo(realOwner);
        assertThat(reloaded.getStudentsCount()).isZero();
        assertThat(reloaded.getLastPublishedAt()).isAfter(java.time.LocalDateTime.of(2021, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("publish and unpublish are routes of their own")
    void lifecycleRoutes() throws Exception {
        var course = publishedCourse();

        mockMvc.perform(post(BASE + "/{id}/unpublish", course.getId())
                        .with(user(instructorUser)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.hasUpdatesSincePublish").value(false));

        mockMvc.perform(post(BASE + "/{id}/publish", course.getId())
                        .with(user(instructorUser)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    @DisplayName("the reorder route takes ids and answers with the reordered course")
    void reorderRoute() throws Exception {
        var course = publishedCourse();
        var ids = moduleIdsOf(course);

        mockMvc.perform(patch(BASE + "/{id}/modules/order", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("moduleIds", List.of(ids.get(1), ids.get(0))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].title").value("Two"))
                .andExpect(jsonPath("$.data.modules[1].title").value("One"));

        assertThat(persistedModuleTitles(course.getId())).containsExactly("Two", "One");
    }

    @Test
    @DisplayName("a reorder naming a module of somebody else's course is refused with a message, not a stack trace")
    void reorderRejectsForeignModules() throws Exception {
        var course = publishedCourse();
        var other = courseService.createCourse(newInstructorUser(),
                modularCourse("Elsewhere", CourseStatus.PUBLISHED, module("Theirs", lesson("x"))));

        mockMvc.perform(patch(BASE + "/{id}/modules/order", course.getId())
                        .with(user(instructorUser)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "moduleIds", List.of(moduleIdsOf(course).get(0), moduleIdsOf(other).get(0))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    @DisplayName("an anonymous caller reaches none of it")
    void anonymousIsRefused() throws Exception {
        var course = publishedCourse();

        mockMvc.perform(post(BASE + "/{id}/publish", course.getId()).with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(BASE + "/{id}/modules/order", course.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"moduleIds\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("another instructor is refused at the service boundary, and nothing changes")
    void anotherInstructorIsRefused() throws Exception {
        var course = publishedCourse();
        var intruder = newInstructorUser();

        mockMvc.perform(post(BASE + "/{id}/unpublish", course.getId())
                        .with(user(intruder)).with(csrf()))
                .andExpect(status().isBadRequest());

        assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);
    }
}
