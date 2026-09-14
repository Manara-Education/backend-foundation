package com.manara.backend.course.integration;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.dto.SubscriptionPlanResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.StreamSupport;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public catalogue, asked the way an anonymous browser asks it: over HTTP, through the real
 * security filter chain, with no session and no credentials.
 *
 * <p>Nothing here calls {@code PublicCourseService}. Every assertion is about a response a visitor
 * could actually receive, and prices are cross-checked against the signed-in course screen, which is
 * what the checkout button is rendered from, so "the same price everywhere" is shown rather than
 * assumed.
 *
 * <p>The database is shared with every other test class and never cleaned, so assertions are about
 * the courses each test creates, found by id. The one exception is the total, which is compared
 * with a count taken from the database under the same rule.
 */
class PublicCourseApiTest extends AbstractCourseAuthoringTest {

    private static final String LIST = "/api/v1/public/courses";
    private static final String DETAIL = "/api/v1/public/courses/{courseId}";

    @Autowired WebApplicationContext context;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static org.springframework.test.web.servlet.setup.MockMvcConfigurer springSecurity() {
        return org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity();
    }

    // ─── fixtures ────────────────────────────────────────────────────────────

    private Long course(String title, CourseStatus status, CourseVisibility visibility) {
        return courseService.createCourse(instructorUser, flatCourse(title, status, visibility, lesson("L1"))).getId();
    }

    private Long purchaseCourse(String title, String price) {
        var request = flatCourse(title, CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.PURCHASE);
        request.setPurchasePrice(new BigDecimal(price));
        return courseService.createCourse(instructorUser, request).getId();
    }

    private Long subscriptionCourse(String title, SubscriptionPlanRequest... plans) {
        var request = flatCourse(title, CourseStatus.PUBLISHED, CourseVisibility.PUBLIC, lesson("L1"));
        request.setAccessType(CourseAccessType.SUBSCRIPTION);
        request.setSubscriptionPlans(List.of(plans));
        return courseService.createCourse(instructorUser, request).getId();
    }

    // ─── asking ──────────────────────────────────────────────────────────────

    private String body(org.springframework.test.web.servlet.RequestBuilder request, int expectedStatus)
            throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private JsonNode data(String body) {
        return json.readTree(body).get("data");
    }

    private JsonNode page(int page, int size) throws Exception {
        return data(body(get(LIST).param("page", String.valueOf(page)).param("size", String.valueOf(size)), 200));
    }

    private JsonNode detail(Long courseId) throws Exception {
        return data(body(get(DETAIL, courseId), 200));
    }

    /** The course's card on the first page. Courses are newest first, and these were just created. */
    private JsonNode listEntry(Long courseId) throws Exception {
        return items(page(0, 50)).stream()
                .filter(item -> item.get("id").asLong() == courseId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("course " + courseId + " is not on the first page"));
    }

    private static List<JsonNode> items(JsonNode page) {
        return StreamSupport.stream(page.get("items").spliterator(), false).toList();
    }

    private static List<Long> ids(JsonNode page) {
        return items(page).stream().map(item -> item.get("id").asLong()).toList();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private long discoverableCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM courses WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC'", Long.class);
    }

    private long unusedCourseId() {
        return jdbcTemplate.queryForObject("SELECT COALESCE(max(id), 0) + 100000 FROM courses", Long.class);
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("an anonymous visitor")
    class Anonymous {

        @Test
        @DisplayName("can list and open an eligible course, and both carry the same offer")
        void listsAndOpensAnEligibleCourse() throws Exception {
            Long id = purchaseCourse("Algebra", "450.00");

            JsonNode card = listEntry(id);
            JsonNode page = detail(id);

            assertThat(card.get("title").asString()).isEqualTo("Algebra");
            assertThat(page.get("title").asString()).isEqualTo("Algebra");
            assertThat(page.get("description").asString()).startsWith("Algebra description");
            assertThat(page.get("lessonCount").asInt()).isEqualTo(1);
            assertThat(page.get("offer")).isEqualTo(card.get("offer"));
            assertThat(page.get("offer").get("accessType").asString()).isEqualTo("PURCHASE");
            assertThat(page.get("offer").get("pricingStatus").asString()).isEqualTo("PRICED");
            assertThat(page.get("offer").get("currency").asString()).isEqualTo("EGP");
            assertThat(page.get("offer").get("purchasePrice").decimalValue()).isEqualByComparingTo("450.00");
        }

        @Test
        @DisplayName("receives exactly the allowlisted fields, with money at two decimal places")
        void receivesTheAllowlistOnTheWire() throws Exception {
            Long id = purchaseCourse("Wire", "450");

            String listBody = body(get(LIST).param("size", "50"), 200);
            String detailBody = body(get(DETAIL, id), 200);

            assertThat(fieldNames(data(listBody))).containsExactly("items", "page", "size", "totalItems", "totalPages");
            assertThat(fieldNames(listEntry(id))).containsExactly(
                    "id", "title", "subtitle", "imageUrl", "instructorName", "durationSeconds", "lessonCount", "offer");
            assertThat(fieldNames(data(detailBody))).containsExactly(
                    "id", "title", "subtitle", "description", "imageUrl", "instructorName", "durationSeconds",
                    "lessonCount", "offer");
            assertThat(detailBody).contains("\"purchasePrice\":450.00");
            assertThat(json.readTree(detailBody).get("status").asString()).isEqualTo("success");
        }

        @Test
        @DisplayName("gets the same answer as a signed-in learner — nothing depends on who asks")
        void answerDoesNotDependOnTheCaller() throws Exception {
            Long id = purchaseCourse("Same for all", "300.00");
            User learner = newStudentUser();

            String anonymous = body(get(DETAIL, id), 200);
            String signedInBody = body(get(DETAIL, id).with(signedIn(learner)), 200);

            assertThat(signedInBody).isEqualTo(anonymous);
        }

        @Test
        @DisplayName("is told not to cache the answer")
        void responsesAreNotCached() throws Exception {
            Long id = course("No store", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

            String cacheControl = mockMvc.perform(get(DETAIL, id)).andReturn().getResponse().getHeader("Cache-Control");

            assertThat(cacheControl).contains("no-store");
        }
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        @DisplayName("draft, private and private-draft courses are not listed")
        void ineligibleCoursesAreNotListed() throws Exception {
            Long live = course("Live", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
            Long draft = course("Draft", CourseStatus.DRAFT, CourseVisibility.PUBLIC);
            Long hidden = course("Hidden", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE);
            Long hiddenDraft = course("Hidden draft", CourseStatus.DRAFT, CourseVisibility.PRIVATE);

            List<Long> listed = ids(page(0, 50));

            assertThat(listed).contains(live).doesNotContain(draft, hidden, hiddenDraft);
        }

        /**
         * The detail route answers a draft, a private course and an id nobody ever used with the same
         * status and the same body, differing only in the id the caller sent. A visitor probing ids
         * learns nothing about which exist.
         */
        @Test
        @DisplayName("draft, private and missing ids are the same 404, revealing nothing")
        void ineligibleAndMissingAreIndistinguishable() throws Exception {
            Long draft = course("Draft detail", CourseStatus.DRAFT, CourseVisibility.PUBLIC);
            Long hidden = course("Private detail", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE);
            Long hiddenDraft = course("Private draft detail", CourseStatus.DRAFT, CourseVisibility.PRIVATE);
            long missing = unusedCourseId();

            String missingBody = body(get(DETAIL, missing), 404).replace(String.valueOf(missing), "{id}");
            for (Long id : List.of(draft, hidden, hiddenDraft)) {
                String ineligibleBody = body(get(DETAIL, id), 404);
                assertThat(ineligibleBody.replace(String.valueOf(id), "{id}"))
                        .as("course %d must be answered exactly like an id that does not exist", id)
                        .isEqualTo(missingBody);
                assertThat(ineligibleBody).doesNotContain("Private detail", "Draft detail", "Private draft detail");
            }
            assertThat(json.readTree(missingBody).get("status").asString()).isEqualTo("error");
        }

        @Test
        @DisplayName("the total counts eligible courses only — filtering happens before counting")
        void totalCountsEligibleCoursesOnly() throws Exception {
            course("Counted", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
            course("Not counted", CourseStatus.PUBLISHED, CourseVisibility.PRIVATE);
            course("Not counted either", CourseStatus.DRAFT, CourseVisibility.PUBLIC);

            JsonNode first = page(0, 1);

            assertThat(first.get("totalItems").asLong()).isEqualTo(discoverableCount());
            assertThat(first.get("totalPages").asLong()).isEqualTo(discoverableCount());
        }
    }

    @Nested
    @DisplayName("pagination")
    class Pagination {

        @Test
        @DisplayName("pages are newest first, stable and disjoint")
        void pagesAreOrderedAndDisjoint() throws Exception {
            Long first = course("P1", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
            Long second = course("P2", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);
            Long third = course("P3", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

            List<Long> paged = new ArrayList<>();
            for (int p = 0; p < 3; p++) {
                JsonNode one = page(p, 1);
                assertThat(one.get("page").asInt()).isEqualTo(p);
                assertThat(one.get("size").asInt()).isEqualTo(1);
                paged.addAll(ids(one));
            }

            assertThat(paged).containsExactly(third, second, first);
            assertThat(ids(page(0, 3))).containsExactly(third, second, first);
        }

        @Test
        @DisplayName("the default page is the first twelve")
        void defaultsToTheFirstTwelve() throws Exception {
            course("Default", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

            JsonNode defaults = data(body(get(LIST), 200));

            assertThat(defaults.get("page").asInt()).isZero();
            assertThat(defaults.get("size").asInt()).isEqualTo(12);
            assertThat(items(defaults).size()).isLessThanOrEqualTo(12);
        }

        @Test
        @DisplayName("a page past the end is empty, with the real totals")
        void pastTheEndIsEmpty() throws Exception {
            course("Last", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

            JsonNode beyond = page(1_000_000, 50);

            assertThat(items(beyond)).isEmpty();
            assertThat(beyond.get("totalItems").asLong()).isEqualTo(discoverableCount());
        }

        @Test
        @DisplayName("a size above 50, below 1, or a negative page is refused")
        void outOfRangePagingIsRefused() throws Exception {
            body(get(LIST).param("size", "51"), 400);
            body(get(LIST).param("size", "0"), 400);
            body(get(LIST).param("page", "-1"), 400);
            body(get(LIST).param("size", "50"), 200);
        }
    }

    @Nested
    @DisplayName("prices")
    class Prices {

        @Test
        @DisplayName("a free course is FREE, with no currency and no amount")
        void freeCourse() throws Exception {
            Long id = course("Free", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

            JsonNode offer = detail(id).get("offer");

            assertThat(offer.get("accessType").asString()).isEqualTo("FREE");
            assertThat(offer.get("pricingStatus").asString()).isEqualTo("FREE");
            assertThat(offer.get("currency").isNull()).isTrue();
            assertThat(offer.get("purchasePrice").isNull()).isTrue();
            assertThat(offer.get("plans").isEmpty()).isTrue();
        }

        @Test
        @DisplayName("a purchase price is the one the signed-in course screen and checkout use")
        void purchasePriceMatchesTheSignedInScreen() throws Exception {
            Long id = purchaseCourse("Matched", "275.50");
            User learner = newStudentUser();

            BigDecimal signedInPrice = courseService.getCourseDetails(learner, id, CourseViewMode.DISCOVER)
                    .getCourse().getPurchasePrice();

            assertThat(listEntry(id).get("offer").get("purchasePrice").decimalValue()).isEqualByComparingTo(signedInPrice);
            assertThat(detail(id).get("offer").get("purchasePrice").decimalValue()).isEqualByComparingTo(signedInPrice);
            assertThat(reload(id).getPurchasePrice()).isEqualByComparingTo("275.50");
        }

        @Test
        @DisplayName("a subscription lists its active plans, with the ids and prices checkout accepts")
        void subscriptionPlansMatchCheckout() throws Exception {
            Long id = subscriptionCourse("Plans",
                    plan("Monthly", 1, SubscriptionUnit.MONTH, "120.00"),
                    plan("Yearly", 12, SubscriptionUnit.MONTH, "900.00"));
            jdbcTemplate.update("UPDATE subscription_plans SET retired_at = now() WHERE course_id = ? AND name = 'Yearly'", id);
            User learner = newStudentUser();

            List<SubscriptionPlanResponse> offered = courseService
                    .getCourseDetails(learner, id, CourseViewMode.DISCOVER).getCourse().getSubscriptionPlans();
            JsonNode cardOffer = listEntry(id).get("offer");
            JsonNode detailOffer = detail(id).get("offer");

            assertThat(detailOffer).isEqualTo(cardOffer);
            assertThat(detailOffer.get("pricingStatus").asString()).isEqualTo("PRICED");
            assertThat(detailOffer.get("purchasePrice").isNull()).isTrue();
            JsonNode plans = detailOffer.get("plans");
            assertThat(plans.size()).isEqualTo(1).isEqualTo(offered.size());
            assertThat(plans.get(0).get("id").asLong()).isEqualTo(offered.getFirst().getId());
            assertThat(plans.get(0).get("name").asString()).isEqualTo("Monthly");
            assertThat(plans.get(0).get("duration").asInt()).isEqualTo(1);
            assertThat(plans.get(0).get("unit").asString()).isEqualTo("MONTH");
            assertThat(plans.get(0).get("price").decimalValue()).isEqualByComparingTo(offered.getFirst().getPrice());
        }

        @Test
        @DisplayName("a subscription whose plans were all retired is UNAVAILABLE on both routes")
        void retiredPlansLeaveNothingToSell() throws Exception {
            Long id = subscriptionCourse("All retired", plan("Monthly", 1, SubscriptionUnit.MONTH, "120.00"));
            jdbcTemplate.update("UPDATE subscription_plans SET retired_at = now() WHERE course_id = ?", id);

            for (JsonNode offer : List.of(listEntry(id).get("offer"), detail(id).get("offer"))) {
                assertThat(offer.get("accessType").asString()).isEqualTo("SUBSCRIPTION");
                assertThat(offer.get("pricingStatus").asString()).isEqualTo("UNAVAILABLE");
                assertThat(offer.get("plans").isEmpty()).isTrue();
            }
        }

        @Test
        @DisplayName("a purchase course with no stored price is UNAVAILABLE on both routes — never free")
        void missingPriceIsUnavailableNotFree() throws Exception {
            Long id = purchaseCourse("Lost its price", "100.00");
            jdbcTemplate.update("UPDATE courses SET price = NULL WHERE id = ?", id);

            for (JsonNode offer : List.of(listEntry(id).get("offer"), detail(id).get("offer"))) {
                assertThat(offer.get("accessType").asString()).isEqualTo("PURCHASE");
                assertThat(offer.get("pricingStatus").asString()).isEqualTo("UNAVAILABLE");
                assertThat(offer.get("currency").asString()).isEqualTo("EGP");
                assertThat(offer.get("purchasePrice").isNull()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("everything else stays protected")
    class Boundaries {

        @Test
        @DisplayName("the learner catalogue, course screen, checkout and instructor routes still require a session")
        void protectedRoutesStillRequireASession() throws Exception {
            Long id = purchaseCourse("Protected", "100.00");

            mockMvc.perform(get("/api/v1/student/courses")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/student/courses/{id}", id).param("mode", "DISCOVER"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post("/api/v1/student/courses/{id}/checkout", id).with(csrf())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/instructor/courses")).andExpect(status().isUnauthorized());
            assertThat(enrollmentRepository.count()).isNotNegative();
        }

        @Test
        @DisplayName("only GET is public on the catalogue paths; every write is refused")
        void onlyGetIsPublic() throws Exception {
            Long id = course("Read only", CourseStatus.PUBLISHED, CourseVisibility.PUBLIC);

            mockMvc.perform(post(LIST).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(put(DETAIL, id).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(delete(DETAIL, id).with(csrf())).andExpect(status().isUnauthorized());
            // Without the token the CSRF filter refuses first, exactly as for every other write.
            mockMvc.perform(post(LIST).contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden());

            assertThat(reload(id).getTitle()).isEqualTo("Read only");
        }
    }
}
