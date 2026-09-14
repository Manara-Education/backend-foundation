package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.user.model.User;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.contentLesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonWithQuiz;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.quiz;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * TECH-8: the public course API, attacked rather than used.
 *
 * <p>{@code PublicCourseApiTest} shows the endpoints do what they are for. This file shows what they
 * must not do — serve protected content, reveal a course that is not on offer, keep serving one that
 * has been withdrawn, or let an anonymous caller make a read expensive — and every assertion here
 * is written so that it fails if the protection it names is removed.
 *
 * <p>Everything is asked over HTTP through the real filter chain with no session, which is the only
 * vantage point that matters for an endpoint nobody has to sign in to.
 */
class PublicCourseApiHardeningTest extends AbstractCourseAuthoringTest {

    private static final String LIST = "/api/v1/public/courses";
    private static final String DETAIL = "/api/v1/public/courses/{courseId}";

    @Autowired WebApplicationContext context;
    @Autowired EntityManagerFactory entityManagerFactory;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private MockHttpServletResponse respond(RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String body(RequestBuilder request, int expectedStatus) throws Exception {
        MockHttpServletResponse response = respond(request);
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expectedStatus);
        return response.getContentAsString();
    }

    private JsonNode data(String body) {
        return json.readTree(body).get("data");
    }

    private List<Long> listedIds() throws Exception {
        JsonNode page = data(body(get(LIST).param("size", "50"), 200));
        return StreamSupport.stream(page.get("items").spliterator(), false)
                .map(item -> item.get("id").asLong())
                .toList();
    }

    private long totalItems() throws Exception {
        return data(body(get(LIST).param("size", "1"), 200)).get("totalItems").asLong();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private InstructorCourseResponse publicCourse(String title) {
        return courseService.createCourse(instructorUser,
                flatCourse(title, CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1")));
    }

    private InstructorCourseResponse save(InstructorCourseResponse course,
                                          java.util.function.Consumer<com.manara.backend.course.dto.CourseRequest> edit) {
        var request = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
        edit.accept(request);
        return courseService.updateCourse(instructorUser, course.getId(), request);
    }

    /** How many SQL statements one request costs, read from Hibernate's own counters. */
    private long statementsFor(RequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThat(respond(request).getStatus()).isEqualTo(200);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("overexposure")
    class Overexposure {

        /**
         * A course carrying every kind of protected content — a video lesson, a rich-content lesson,
         * a quiz with its answer key, an enrolled learner — beside a private and a draft course, all
         * marked with one unique string. Neither route may return a byte of any of it.
         */
        @Test
        @DisplayName("lessons, media, quiz answers, learners, account data and hidden courses never appear")
        void nothingProtectedIsServed() throws Exception {
            String marker = "LEAK" + System.nanoTime();
            instructorProfile.setBio("Bio " + marker);
            instructorRepository.save(instructorProfile);
            var request = flatCourse("Public " + marker.substring(0, 4), CourseStatus.PUBLISHED, CourseVisibility.PUBLIC,
                    lesson("Video lesson " + marker),
                    contentLesson("Content lesson " + marker, "Body " + marker),
                    lessonWithQuiz("Quiz lesson " + marker, quiz("Quiz " + marker)));
            request.setAccessType(CourseAccessType.PURCHASE);
            request.setPurchasePrice(new BigDecimal("100.00"));
            var course = courseService.createCourse(instructorUser, request);
            User learner = newStudentUser();
            enroll(learner, course.getId());
            var hidden = courseService.createCourse(instructorUser,
                    flatCourse("Private " + marker, CourseStatus.PUBLISHED, CourseVisibility.PRIVATE, lesson("L1")));
            var draft = courseService.createCourse(instructorUser,
                    flatCourse("Draft " + marker, CourseStatus.DRAFT, CourseVisibility.PUBLIC, lesson("L1")));

            String list = body(get(LIST).param("size", "50"), 200);
            String detail = body(get(DETAIL, course.getId()), 200);
            String served = list + detail
                    + body(get(DETAIL, hidden.getId()), 404)
                    + body(get(DETAIL, draft.getId()), 404);

            // Values: unique to this test, so they can be searched for across every body even though
            // the shared database puts other test classes' courses on the same page.
            assertThat(served)
                    .doesNotContain(marker)
                    .doesNotContain(learner.getEmail())
                    .doesNotContain(instructorUser.getEmail())
                    .doesNotContain("dQw4w9WgXcQ");
            assertThat(detail).doesNotContainIgnoringCase("youtube");

            // Keys: a protected field is a property name, whatever its value. Searched structurally,
            // because another course's title may legitimately contain a word such as "quiz".
            List<String> keys = new ArrayList<>();
            collectKeys(data(list), keys);
            collectKeys(data(detail), keys);
            assertThat(keys).doesNotContainAnyElementsOf(List.of(
                    "videoUrl", "videoProvider", "richContent", "lessons", "modules", "quiz", "finalQuiz",
                    "questions", "options", "correctOptionId", "explanation", "enrolled", "enrollment",
                    "progress", "access", "email", "password", "bio", "studentsCount", "status", "visibility",
                    "revision", "instructorId", "createdAt", "updatedAt", "orderIndex", "retiredAt"));
            assertThat(listedIds()).contains(course.getId()).doesNotContain(hidden.getId(), draft.getId());
        }

        private void collectKeys(JsonNode node, List<String> keys) {
            if (node.isObject()) {
                node.propertyNames().forEach(name -> {
                    keys.add(name);
                    collectKeys(node.get(name), keys);
                });
            } else if (node.isArray()) {
                node.forEach(child -> collectKeys(child, keys));
            }
        }

        @Test
        @DisplayName("an error body is the envelope and a message — no code, trace or exception detail")
        void errorBodiesSayNothingExtra() throws Exception {
            var hidden = courseService.createCourse(instructorUser,
                    flatCourse("Hidden", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE, lesson("L1")));

            for (String error : List.of(
                    body(get(DETAIL, hidden.getId()), 404),
                    body(get("/api/v1/public/courses/not-a-number"), 400),
                    body(get(LIST).param("size", "5000"), 400))) {
                JsonNode node = json.readTree(error);
                assertThat(fieldNames(node)).containsExactly("status", "errors");
                assertThat(error).doesNotContain("Exception", "java.", "org.", "trace", "Hidden");
            }
        }
    }

    @Nested
    @DisplayName("hostile input")
    class HostileInput {

        @ParameterizedTest(name = "course id \"{0}\" is a 400")
        @ValueSource(strings = {"abc", "1.5", "1e3", "0", "-1", "+5", "99999999999999999999"})
        void malformedIdsAreRefused(String raw) throws Exception {
            String body = body(get("/api/v1/public/courses/" + raw), 400);

            assertThat(body).doesNotContain(raw);
        }

        /**
         * The id is read strictly, as a canonical decimal. Left to Spring's number conversion,
         * {@code 0x10} was decoded as hexadecimal and {@code +16} and {@code 016} as sixteen, so one
         * public course answered at several addresses. Nothing was exposed that was not already
         * public, but a resource should have one address, and a parser that surprises people is one
         * nobody reviews correctly.
         */
        @Test
        @DisplayName("other spellings of a real public id are refused, not treated as aliases")
        void nonCanonicalIdsAreNotAliases() throws Exception {
            long id = publicCourse("One address").getId();

            body(get(DETAIL, id), 200);
            for (String alias : List.of("0" + id, "+" + id, "0x" + Long.toHexString(id), "0X" + Long.toHexString(id))) {
                body(get("/api/v1/public/courses/" + alias), 400);
            }
        }

        @Test
        @DisplayName("every paging boundary is enforced by the server")
        void pagingBoundaries() throws Exception {
            publicCourse("Boundary");

            body(get(LIST).param("size", "1"), 200);
            body(get(LIST).param("size", "50"), 200);
            body(get(LIST).param("size", "51"), 400);
            body(get(LIST).param("size", "1000000"), 400);
            body(get(LIST).param("size", "-5"), 400);
            body(get(LIST).param("size", "1.5"), 400);
            body(get(LIST).param("page", "abc"), 400);
            body(get(LIST).param("page", "99999999999"), 400);
            // An offset past what the database's paging can express is refused, not overflowed.
            body(get(LIST).param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "50"), 400);
            // The largest expressible offset is simply an empty page.
            JsonNode last = data(body(get(LIST).param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "1"), 200));
            assertThat(last.get("items").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a rejected value is not echoed back")
        void rejectedValuesAreNotReflected() throws Exception {
            String body = body(get(LIST).param("size", "<script>alert(1)</script>"), 400);

            assertThat(body).doesNotContain("<script>");
        }

        /**
         * There is no sort or filter parameter, and the ones a caller might try — including a sort
         * by a joined column that could order results by something private — change nothing.
         */
        @Test
        @DisplayName("sort and filter parameters are ignored, not interpreted")
        void unknownParametersChangeNothing() throws Exception {
            publicCourse("Ordered");
            String plain = body(get(LIST).param("size", "10"), 200);

            for (String[] extra : List.of(
                    new String[] {"sort", "title,asc"},
                    new String[] {"sort", "instructor.user.email,desc"},
                    new String[] {"status", "DRAFT"},
                    new String[] {"visibility", "PRIVATE"},
                    new String[] {"accessType", "FREE"},
                    new String[] {"instructorId", "1"})) {
                assertThat(body(get(LIST).param("size", "10").param(extra[0], extra[1]), 200))
                        .as("?%s=%s must not change the result", extra[0], extra[1])
                        .isEqualTo(plain);
            }
        }
    }

    @Nested
    @DisplayName("withdrawal takes effect at once")
    class Transitions {

        @Test
        @DisplayName("public → private → public, through the editor, on both routes")
        void goingPrivateWithdrawsImmediately() throws Exception {
            var course = publicCourse("Withdrawn");
            assertThat(listedIds()).contains(course.getId());

            var privateCourse = save(course, request -> request.setVisibility(CourseVisibility.PRIVATE));
            assertThat(listedIds()).doesNotContain(course.getId());
            body(get(DETAIL, course.getId()), 404);

            save(privateCourse, request -> request.setVisibility(CourseVisibility.PUBLIC));
            assertThat(listedIds()).contains(course.getId());
            body(get(DETAIL, course.getId()), 200);
        }

        @Test
        @DisplayName("unpublishing withdraws the course; publishing restores it")
        void unpublishingWithdrawsImmediately() throws Exception {
            var course = publicCourse("Unpublished");

            courseService.unpublish(instructorUser, course.getId());
            assertThat(listedIds()).doesNotContain(course.getId());
            body(get(DETAIL, course.getId()), 404);

            courseService.publish(instructorUser, course.getId());
            assertThat(listedIds()).contains(course.getId());
        }

        @Test
        @DisplayName("a new price and a removed plan are served on the next request")
        void pricingChangesAreNeverStale() throws Exception {
            var request = flatCourse("Repriced", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
            request.setAccessType(CourseAccessType.SUBSCRIPTION);
            request.setSubscriptionPlans(List.of(
                    plan("Monthly", 1, SubscriptionUnit.MONTH, "120.00"),
                    plan("Weekly", 1, SubscriptionUnit.WEEK, "40.00")));
            var course = courseService.createCourse(instructorUser, request);
            assertThat(data(body(get(DETAIL, course.getId()), 200)).get("offer").get("plans").size()).isEqualTo(2);

            save(course, edit -> edit.setSubscriptionPlans(List.of(edit.getSubscriptionPlans().getFirst())));
            JsonNode plans = data(body(get(DETAIL, course.getId()), 200)).get("offer").get("plans");
            assertThat(plans.size()).isEqualTo(1);
            assertThat(plans.get(0).get("name").asString()).isEqualTo("Monthly");

            save(course, edit -> {
                edit.setAccessType(CourseAccessType.PURCHASE);
                edit.setPurchasePrice(new BigDecimal("250.00"));
                edit.setSubscriptionPlans(null);
            });
            JsonNode offer = data(body(get(DETAIL, course.getId()), 200)).get("offer");
            assertThat(offer.get("accessType").asString()).isEqualTo("PURCHASE");
            assertThat(offer.get("purchasePrice").decimalValue()).isEqualByComparingTo("250.00");
            assertThat(offer.get("plans").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("no response may be cached: success, not-found and refusal all say no-store")
        void nothingIsCacheable() throws Exception {
            var course = publicCourse("Uncached");
            var hidden = courseService.createCourse(instructorUser,
                    flatCourse("Hidden uncached", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE, lesson("L1")));

            for (RequestBuilder request : List.of(
                    get(LIST), get(DETAIL, course.getId()), get(DETAIL, hidden.getId()), get(LIST).param("size", "0"))) {
                assertThat(respond(request).getHeader("Cache-Control")).contains("no-store");
            }
        }
    }

    @Nested
    @DisplayName("counts")
    class Counts {

        @Test
        @DisplayName("a new private or draft course does not move the total; a public one does")
        void totalsRevealNothingHidden() throws Exception {
            long before = totalItems();

            courseService.createCourse(instructorUser,
                    flatCourse("Counted private", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE, lesson("L1")));
            courseService.createCourse(instructorUser,
                    flatCourse("Counted draft", CourseStatus.DRAFT, CourseVisibility.PUBLIC, lesson("L1")));
            assertThat(totalItems()).isEqualTo(before);

            publicCourse("Counted public");
            assertThat(totalItems()).isEqualTo(before + 1);
        }
    }

    @Nested
    @DisplayName("cost of a read")
    class Bounded {

        /**
         * The page's plans are loaded in one query whatever the page holds, and the instructor
         * with the page, so a larger page is not more queries. Measured with Hibernate's statement
         * counter on a page made entirely of subscription courses — the most expensive kind.
         */
        @Test
        @DisplayName("a page costs the same number of statements at 2 items as at 20 — no N+1")
        void pageCostIsIndependentOfItsSize() throws Exception {
            for (int i = 0; i < 20; i++) {
                var request = flatCourse("Plan course " + i, CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
                request.setAccessType(CourseAccessType.SUBSCRIPTION);
                request.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00")));
                courseService.createCourse(instructorUser, request);
            }

            long small = statementsFor(get(LIST).param("size", "2"));
            long large = statementsFor(get(LIST).param("size", "20"));

            assertThat(large).isEqualTo(small);
            assertThat(small).as("page, count and plans").isLessThanOrEqualTo(3);
        }

        @Test
        @DisplayName("a detail costs one statement, or two when it has plans")
        void detailCostIsConstant() throws Exception {
            var purchase = flatCourse("One statement", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
            purchase.setAccessType(CourseAccessType.PURCHASE);
            purchase.setPurchasePrice(new BigDecimal("80.00"));
            var bought = courseService.createCourse(instructorUser, purchase);
            var subscription = flatCourse("Two statements", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
            subscription.setAccessType(CourseAccessType.SUBSCRIPTION);
            subscription.setSubscriptionPlans(List.of(plan("Monthly", 1, SubscriptionUnit.MONTH, "100.00")));
            var subscribed = courseService.createCourse(instructorUser, subscription);

            assertThat(statementsFor(get(DETAIL, bought.getId()))).isEqualTo(1);
            assertThat(statementsFor(get(DETAIL, subscribed.getId()))).isEqualTo(2);
        }
    }
}
