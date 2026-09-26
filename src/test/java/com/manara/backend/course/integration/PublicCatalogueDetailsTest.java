package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.json.Patch;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.contentLesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public course page's new sections — outline, category, instructor — asked for anonymously,
 * and the instructor-side authoring that feeds them.
 */
class PublicCatalogueDetailsTest extends AbstractCourseAuthoringTest {

    private static final String DETAIL = "/api/v1/public/courses/{courseId}";
    private static final String LIST = "/api/v1/public/courses";

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mockMvc;

    @Autowired
    WebApplicationContext context;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── Outline ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A flat course's outline is its lessons in order, titles and lengths only")
    void flatOutline() throws Exception {
        Long id = courseService.createCourse(instructorUser, flatCourse("Flat outline", CourseStatus.PUBLISHED,
                CourseVisibility.PUBLIC, lesson("First"), contentLesson("Second", "SECRET-BODY-TEXT"))).getId();
        jdbcTemplate.update("UPDATE lessons SET duration = 125 WHERE course_id = ? AND title = 'First'", id);

        String body = anonymousBody(id);
        JsonNode outline = data(body).get("outline");

        assertThat(outline).hasSize(1);
        assertThat(outline.get(0).get("moduleTitle").isNull()).isTrue();
        assertThat(titles(outline.get(0))).containsExactly("First", "Second");
        assertThat(fieldNames(outline.get(0).get("lessons").get(0))).containsExactly("id", "title", "durationSeconds", "preview");
        assertThat(outline.get(0).get("lessons").get(0).get("durationSeconds").asInt()).isEqualTo(125);
        assertThat(outline.get(0).get("lessons").get(1).get("durationSeconds").isNull()).isTrue();
        assertThat(outline.get(0).get("lessons").get(0).get("preview").asBoolean()).isFalse();
        assertThat(body)
                .doesNotContain("SECRET-BODY-TEXT")
                .doesNotContain("youtube.com")
                .doesNotContain("First description");
    }

    @Test
    @DisplayName("A module course lists its modules in order, an empty one included")
    void modularOutline() throws Exception {
        Long id = courseService.createCourse(instructorUser, modularCourse("Modular outline", CourseStatus.PUBLISHED,
                CourseVisibility.PUBLIC,
                module("Module A", lesson("A1"), lesson("A2")),
                module("Module B"),
                module("Module C", lesson("C1")))).getId();

        JsonNode outline = data(anonymousBody(id)).get("outline");

        assertThat(outline).hasSize(3);
        assertThat(outline.get(0).get("moduleTitle").asString()).isEqualTo("Module A");
        assertThat(titles(outline.get(0))).containsExactly("A1", "A2");
        assertThat(outline.get(1).get("lessons")).isEmpty();
        assertThat(titles(outline.get(2))).containsExactly("C1");
    }

    @Test
    @DisplayName("Draft and private courses expose no outline at all")
    void unpublishedCoursesStayHidden() throws Exception {
        Long draft = courseService.createCourse(instructorUser, flatCourse("Draft outline", CourseStatus.DRAFT,
                CourseVisibility.PUBLIC, lesson("Hidden draft lesson"))).getId();
        Long hidden = courseService.createCourse(instructorUser, flatCourse("Private outline", CourseStatus.PUBLISHED,
                CourseVisibility.PRIVATE, lesson("Hidden private lesson"))).getId();

        for (Long id : List.of(draft, hidden)) {
            String body = mockMvc.perform(get(DETAIL, id)).andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("Hidden");
        }
    }

    // ── Category ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("An instructor categorises a course and the public card and page show it")
    void categoryIsAuthoredAndShown() throws Exception {
        Long categoryId = category("البرمجة", "indigo", true);
        CourseRequest request = flatCourse("Categorised", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setCategoryId(Patch.of(categoryId));
        Long id = courseService.createCourse(instructorUser, request).getId();

        JsonNode detail = data(anonymousBody(id));
        assertThat(detail.get("category").get("id").asLong()).isEqualTo(categoryId);
        assertThat(detail.get("category").get("name").asString()).isEqualTo("البرمجة");
        assertThat(detail.get("category").get("color").asString()).isEqualTo("indigo");
        assertThat(listEntry(id).get("category").get("name").asString()).isEqualTo("البرمجة");

        // A metadata save that does not mention the category keeps it; an explicit null clears it.
        CourseRequest rename = flatCourse("Categorised, renamed", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
        rename.setLessons(null);
        rename.setExpectedRevision(reload(id).getRevision());
        courseService.updateCourse(instructorUser, id, rename);
        assertThat(data(anonymousBody(id)).get("category").get("id").asLong()).isEqualTo(categoryId);

        CourseRequest clear = flatCourse("Categorised, renamed", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
        clear.setLessons(null);
        clear.setExpectedRevision(reload(id).getRevision());
        clear.setCategoryId(Patch.of(null));
        courseService.updateCourse(instructorUser, id, clear);
        assertThat(data(anonymousBody(id)).get("category").isNull()).isTrue();
    }

    @Test
    @DisplayName("Only an active category may be assigned, and a retired one disappears from public pages")
    void inactiveCategories() throws Exception {
        Long retired = category("قديم", "slate", false);
        CourseRequest request = flatCourse("Retired category", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setCategoryId(Patch.of(retired));
        assertThatThrownBy(() -> courseService.createCourse(instructorUser, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.categoryInvalid");

        Long active = category("لغات", "teal", true);
        CourseRequest ok = flatCourse("Later retired", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        ok.setCategoryId(Patch.of(active));
        Long id = courseService.createCourse(instructorUser, ok).getId();
        jdbcTemplate.update("UPDATE course_categories SET active = false WHERE id = ?", active);

        assertThat(data(anonymousBody(id)).get("category").isNull()).isTrue();
    }

    @Test
    @DisplayName("The category list is for instructors, active only, in order")
    void categoryListIsForInstructors() throws Exception {
        Long later = category("ب-" + UUID.randomUUID(), "rose", true, 900);
        Long earlier = category("أ-" + UUID.randomUUID(), "amber", true, 899);
        category("hidden-" + UUID.randomUUID(), "sky", false, 898);

        JsonNode list = data(mockMvc.perform(get("/api/v1/instructor/course-categories").with(signedIn(instructorUser)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        list.forEach(node -> ids.add(node.get("id").asLong()));
        assertThat(ids).containsSubsequence(earlier, later);
        list.forEach(node -> assertThat(node.get("name").asString()).doesNotStartWith("hidden-"));

        mockMvc.perform(get("/api/v1/instructor/course-categories").with(signedIn(newStudentUser())))
                .andExpect(status().isBadRequest());
    }

    // ── Instructor card ───────────────────────────────────────────────────────

    @Test
    @DisplayName("The instructor card shows the name, photo and headline, and never the address")
    void instructorCard() throws Exception {
        jdbcTemplate.update("UPDATE users SET avatar_url = '/uploads/instructor-photo.jpg' WHERE id = ?", instructorUser.getId());
        mockMvc.perform(put("/api/v1/profile/instructor").with(csrf()).with(signedIn(instructorUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"headline\":\"  مدرّسة رياضيات منذ عشر سنوات  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headline").value("مدرّسة رياضيات منذ عشر سنوات"));
        Long id = courseService.createCourse(instructorUser, flatCourse("Taught", CourseStatus.PUBLISHED,
                CourseVisibility.PUBLIC, lesson("L1"))).getId();

        String body = anonymousBody(id);
        JsonNode instructor = data(body).get("instructor");
        assertThat(instructor.get("name").asString()).isEqualTo(instructorUser.getFullName());
        assertThat(instructor.get("avatarUrl").asString()).isEqualTo("/uploads/instructor-photo.jpg");
        assertThat(instructor.get("headline").asString()).isEqualTo("مدرّسة رياضيات منذ عشر سنوات");
        assertThat(body).doesNotContain(instructorUser.getEmail());
    }

    @Test
    @DisplayName("The headline is the instructor's alone, bounded, and blank clears it")
    void headlineRules() throws Exception {
        User student = newStudentUser();
        mockMvc.perform(put("/api/v1/profile/instructor").with(csrf()).with(signedIn(student))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"headline\":\"x\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/profile/instructor").with(csrf()).with(signedIn(instructorUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"headline\":\"" + "ا".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/v1/profile/instructor").with(csrf()).with(signedIn(instructorUser))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"headline\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headline").doesNotExist());
        mockMvc.perform(get("/api/v1/profile/instructor").with(signedIn(instructorUser)))
                .andExpect(status().isOk());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long category(String name, String color, boolean active) {
        return category(name, color, active, 0);
    }

    private Long category(String name, String color, boolean active, int sort) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO course_categories (slug, name_ar, color_token, sort_order, active)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, Long.class, "test-" + UUID.randomUUID(), name, color, sort, active);
    }

    private String anonymousBody(Long id) throws Exception {
        return mockMvc.perform(get(DETAIL, id)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private JsonNode data(String body) {
        return json.readTree(body).get("data");
    }

    private JsonNode listEntry(Long id) throws Exception {
        JsonNode items = data(mockMvc.perform(get(LIST).param("size", "50")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("items");
        for (JsonNode item : items) {
            if (item.get("id").asLong() == id) return item;
        }
        throw new AssertionError("course " + id + " is not on the first page");
    }

    private static List<String> titles(JsonNode group) {
        List<String> titles = new ArrayList<>();
        group.get("lessons").forEach(lesson -> titles.add(lesson.get("title").asString()));
        return titles;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
