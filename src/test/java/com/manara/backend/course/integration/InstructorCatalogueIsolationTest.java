package com.manara.backend.course.integration;

import com.jayway.jsonpath.JsonPath;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.terms.service.TermsVersionRegistry;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SEC-F01. Whether one instructor can read another instructor's unpublished courses.
 *
 * <p>The chain the pentest walked needed nothing but a mailbox: register with {@code "role":
 * "INSTRUCTOR"} — which is allowed, because self-registration is the only way an instructor account
 * comes into existence — confirm the emailed code, and ask {@code GET /api/v1/instructor/courses}.
 * That answered with every course on the platform, another instructor's {@code DRAFT + PRIVATE}
 * course included: its title, description, cover, pricing, author and counts. The role check in
 * front of it was real; the list behind it was simply everybody's.
 *
 * <p>So the instructor here is made the way the attacker made one, through register and
 * verify-otp over HTTP, rather than seeded. A seeded instructor would prove the query is scoped for
 * accounts the tests build, not for the account a stranger can build. Only the administrator is
 * written straight into the database, because there is no other way to make one — which is the
 * point of {@code AuthService}'s allowlist.
 *
 * <p>The owner and the administrator are asserted as carefully as the stranger. A fix that emptied
 * the list for everybody would pass the first test and break the instructor's own course screen;
 * the tests after it are what stop that from counting as a fix.
 */
class InstructorCatalogueIsolationTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@catalogue-isolation.example";
    private static final String PASSWORD = "catalogue isolation passphrase 2026";
    private static final String BASE = "/api/v1/instructor/courses";

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    /** Instructor B, who owns the private draft. */
    private MockHttpSession owner;

    /** Instructor A, a stranger to it, signed up the way the pentest did. */
    private MockHttpSession stranger;

    /** Unique per run, so an absence can never be explained by a title another test happened to use. */
    private String marker;
    private Long privateDraftId;

    @BeforeEach
    void signUpBothInstructorsAndCreateThePrivateDraft() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        removeTestData();
        given(emailService.send(any())).willReturn(new EmailSendResult("stub"));

        owner = signUp("owner" + DOMAIN, Role.INSTRUCTOR);
        stranger = signUp("stranger" + DOMAIN, Role.INSTRUCTOR);

        marker = "SEC-F01 private draft " + UUID.randomUUID();
        privateDraftId = createCourse(owner, marker, "DRAFT", "PRIVATE");
    }

    @AfterEach
    void removeTestData() {
        // Courses first: they hang off the instructor profile. None of the ones made here has
        // lessons, modules, plans or learners, and a draft never publishes a change log row.
        jdbc.update("DELETE FROM courses WHERE instructor_id IN (SELECT i.id FROM instructors i "
                + "JOIN users u ON u.id = i.user_id WHERE u.email LIKE ?)", "%" + DOMAIN);
        jdbc.update("DELETE FROM terms_acceptances WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM instructors WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    @DisplayName("a self-registered instructor's catalogue holds their own courses and nobody else's")
    void aStrangersCatalogueDoesNotContainAnotherInstructorsPrivateDraft() throws Exception {
        Long ownCourseId = createCourse(stranger, "SEC-F01 own course " + UUID.randomUUID(), "DRAFT", "PUBLIC");

        MvcResult catalogue = mockMvc.perform(get(BASE).session(stranger))
                .andExpect(status().isOk())
                .andReturn();

        // The finding itself, asserted by the one thing an attacker reads: the title.
        assertThat(titlesIn(catalogue))
                .as("another instructor's DRAFT + PRIVATE course must not be in this catalogue")
                .doesNotContain(marker);
        assertThat(body(catalogue)).doesNotContain(marker);
        // Exactly the caller's own, not merely "not that one" — the database is shared with every
        // other integration test, so anything scoped short of ownership would still show up here.
        assertThat(idsIn(catalogue))
                .as("the root list is the caller's own courses and nothing else")
                .containsExactly(ownCourseId);

        MvcResult mine = mockMvc.perform(get(BASE + "/my-courses").session(stranger))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(idsIn(mine)).containsExactly(ownCourseId);
    }

    @Test
    @DisplayName("no other instructor-facing read of the course answers a stranger with its metadata")
    void noOtherInstructorReadPathReachesTheCourse() throws Exception {
        // The editor already refused a non-owner before this fix; pinned so it cannot regress.
        String editor = body(mockMvc.perform(get(BASE + "/{id}", privateDraftId).session(stranger))
                .andExpect(status().isBadRequest())
                .andReturn());
        String lessons = body(mockMvc.perform(get(BASE + "/{id}/lessons", privateDraftId).session(stranger))
                .andExpect(status().isNotFound())
                .andReturn());
        String details = body(mockMvc.perform(get("/api/v1/student/courses/{id}", privateDraftId)
                        .param("mode", "DISCOVER").session(stranger))
                .andExpect(status().isNotFound())
                .andReturn());

        assertThat(List.of(editor, lessons, details))
                .as("no refusal may quote the course it refused")
                .noneMatch(response -> response.contains(marker));
    }

    @Test
    @DisplayName("the owning instructor still sees the private draft, in the same shape as before")
    void theOwnerStillSeesTheirCourse() throws Exception {
        mockMvc.perform(get(BASE).session(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(privateDraftId))
                .andExpect(jsonPath("$.data[0].title").value(marker))
                .andExpect(jsonPath("$.data[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.data[0].visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data[0].instructorName").value("Catalogue INSTRUCTOR"));

        assertThat(idsIn(mockMvc.perform(get(BASE + "/my-courses").session(owner))
                .andExpect(status().isOk())
                .andReturn()))
                .containsExactly(privateDraftId);
    }

    @Test
    @DisplayName("an administrator still sees the whole platform, private drafts included")
    void anAdministratorStillSeesEverything() throws Exception {
        User admin = userRepository.save(User.builder()
                .fullName("Catalogue Admin")
                .email("admin" + DOMAIN)
                .password("{noop}irrelevant")
                .emailVerified(true)
                .role(Role.ADMIN)
                .build());

        MvcResult catalogue = mockMvc.perform(get(BASE).with(signedIn(admin)))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(idsIn(catalogue)).contains(privateDraftId);
        assertThat(titlesIn(catalogue)).contains(marker);
    }

    @Test
    @DisplayName("a learner is still refused the instructor catalogue, and discovery still omits the draft")
    void aLearnerIsStillRefusedAndDiscoveryIsUnchanged() throws Exception {
        MockHttpSession learner = signUp("learner" + DOMAIN, Role.STUDENT);

        String refused = body(mockMvc.perform(get(BASE).session(learner))
                .andExpect(status().isBadRequest())
                .andReturn());
        assertThat(refused).doesNotContain(marker);

        MvcResult discovery = mockMvc.perform(get("/api/v1/student/courses").session(learner))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(idsIn(discovery)).doesNotContain(privateDraftId);

        mockMvc.perform(get("/api/v1/student/courses/{id}", privateDraftId)
                        .param("mode", "DISCOVER").session(learner))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Register, read the code the application would have emailed, confirm it — and keep the
     * session verify-otp opened, which is the one a real browser would go on using.
     */
    private MockHttpSession signUp(String email, Role role) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Catalogue %s","email":"%s","password":"%s","role":"%s",
                                 "termsAccepted":true,"termsVersion":"%s"}
                                """.formatted(role.name(), email, PASSWORD, role.name(),
                                termsVersionRegistry.current().orElseThrow().id())))
                .andExpect(status().isCreated());

        MvcResult verified = mockMvc.perform(post("/api/v1/auth/verify-otp").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s"}
                                """.formatted(email, outstandingCode(email))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) verified.getRequest().getSession(false);
        assertThat(session).as("verify-otp must sign the new account in").isNotNull();
        return session;
    }

    private String outstandingCode(String email) {
        return jdbc.queryForObject("""
                SELECT o.code FROM otps o
                  JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.used = false AND o.type = 'EMAIL_VERIFICATION'
                 ORDER BY o.created_at DESC LIMIT 1
                """, String.class, email);
    }

    /** Created over HTTP by the signed-in instructor, as the course wizard does. No lessons, so no video lookup. */
    private Long createCourse(MockHttpSession instructor, String title, String status, String visibility)
            throws Exception {
        MvcResult created = mockMvc.perform(post(BASE).session(instructor).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s","description":"%s description, long enough to mean something",
                                 "structure":"FLAT","lessons":[],"status":"%s","visibility":"%s"}
                                """.formatted(title, title, status, visibility)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(status))
                .andExpect(jsonPath("$.data.visibility").value(visibility))
                .andReturn();
        return ((Number) JsonPath.read(body(created), "$.data.id")).longValue();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static List<Long> idsIn(MvcResult result) throws Exception {
        List<Number> ids = JsonPath.read(body(result), "$.data[*].id");
        return ids.stream().map(Number::longValue).toList();
    }

    private static List<String> titlesIn(MvcResult result) throws Exception {
        return JsonPath.read(body(result), "$.data[*].title");
    }
}
