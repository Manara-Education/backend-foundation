package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.PublicCourseSummaryResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.course.service.PublicOffer;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public catalogue's wire contract, asserted on serialized JSON.
 *
 * <p>On JSON rather than on the records, because JSON is what reaches an anonymous browser: a
 * field added to a nested type, or a Jackson setting that starts dropping nulls, shows up here even
 * if every getter still returns what it did.
 */
class PublicCourseMapperTest {

    private static final String INSTRUCTOR_EMAIL = "instructor-private@manara.test";
    private static final String INSTRUCTOR_BIO = "Private biography text";

    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicCourseMapper mapper = new PublicCourseMapper();

    private static Course course(CourseAccessType accessType, String purchasePrice) {
        User user = User.builder()
                .id(41L)
                .fullName("Dr. Amal Hassan")
                .email(INSTRUCTOR_EMAIL)
                .password("{noop}secret-password")
                .emailVerified(true)
                .role(Role.INSTRUCTOR)
                .build();
        Instructor instructor = Instructor.builder().id(42L).user(user).bio(INSTRUCTOR_BIO).specialization("Math").build();
        return Course.builder()
                .id(7L)
                .title("Algebra for secondary school")
                .subtitle("From equations to functions")
                .description("What the course covers, in plain text.")
                .image("/uploads/3f2a9c1e-8d7b-4c55-9e1f-0a1b2c3d4e5f.webp")
                .duration(5400)
                .lessonCount(12)
                .accessType(accessType)
                .purchasePrice(purchasePrice == null ? null : new BigDecimal(purchasePrice))
                .status(CourseStatus.PUBLISHED)
                .visibility(CourseVisibility.PUBLIC)
                .studentsCount(987)
                .revision(31L)
                .instructor(instructor)
                .build();
    }

    private static SubscriptionPlan plan(long id, String name, int orderIndex, int duration, SubscriptionUnit unit,
                                         String price) {
        return SubscriptionPlan.builder()
                .id(id).name(name).orderIndex(orderIndex).duration(duration).unit(unit)
                .price(new BigDecimal(price))
                .build();
    }

    private JsonNode summaryJson(Course course, List<SubscriptionPlan> plans) {
        return json.valueToTree(mapper.toSummary(course, PublicOffer.of(course, plans)));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    // ─── shape ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the summary is exactly the allowlisted fields")
    void summaryShape() {
        var node = summaryJson(course(CourseAccessType.PURCHASE, "450.00"), List.of());

        assertThat(fieldNames(node)).containsExactly(
                "id", "title", "subtitle", "imageUrl", "instructorName", "durationSeconds", "lessonCount", "offer");
        assertThat(fieldNames(node.get("offer"))).containsExactly(
                "accessType", "pricingStatus", "currency", "purchasePrice", "plans");
        assertThat(node.get("id").asLong()).isEqualTo(7L);
        assertThat(node.get("instructorName").asString()).isEqualTo("Dr. Amal Hassan");
        assertThat(node.get("durationSeconds").asInt()).isEqualTo(5400);
        assertThat(node.get("lessonCount").asInt()).isEqualTo(12);
    }

    @Test
    @DisplayName("the detail is the summary plus the plain-text description, with the same offer")
    void detailShape() {
        var course = course(CourseAccessType.PURCHASE, "450.00");
        var offer = PublicOffer.of(course, List.of());
        JsonNode detail = json.valueToTree(mapper.toDetail(course, offer));
        JsonNode summary = json.valueToTree(mapper.toSummary(course, offer));

        assertThat(fieldNames(detail)).containsExactly(
                "id", "title", "subtitle", "description", "imageUrl", "instructorName", "durationSeconds",
                "lessonCount", "offer");
        assertThat(detail.get("description").asString()).isEqualTo("What the course covers, in plain text.");
        assertThat(detail.get("offer")).isEqualTo(summary.get("offer"));
    }

    @Test
    @DisplayName("a subscription plan is exactly id, name, term and price")
    void planShape() {
        var node = summaryJson(course(CourseAccessType.SUBSCRIPTION, null),
                List.of(plan(9, "Monthly", 0, 1, SubscriptionUnit.MONTH, "120")));

        JsonNode plan = node.get("offer").get("plans").get(0);
        assertThat(fieldNames(plan)).containsExactly("id", "name", "duration", "unit", "price");
        assertThat(plan.get("id").asLong()).isEqualTo(9L);
        assertThat(plan.get("duration").asInt()).isEqualTo(1);
        assertThat(plan.get("unit").asString()).isEqualTo("MONTH");
        assertThat(plan.get("price").decimalValue()).isEqualTo(new BigDecimal("120.00"));
    }

    @Test
    @DisplayName("a page carries its items and bounded counts")
    void pageShape() {
        var course = course(CourseAccessType.FREE, null);
        var items = List.of(mapper.toSummary(course, PublicOffer.of(course, List.of())));

        JsonNode page = json.valueToTree(mapper.toPage(items, 2, 12, 25));

        assertThat(fieldNames(page)).containsExactly("items", "page", "size", "totalItems", "totalPages");
        assertThat(page.get("page").asInt()).isEqualTo(2);
        assertThat(page.get("size").asInt()).isEqualTo(12);
        assertThat(page.get("totalItems").asLong()).isEqualTo(25);
        assertThat(page.get("totalPages").asInt()).isEqualTo(3);
        assertThat(json.valueToTree(mapper.toPage(List.<PublicCourseSummaryResponse>of(), 0, 12, 0))
                .get("totalPages").asInt()).isZero();
    }

    // ─── pricing on the wire ─────────────────────────────────────────────────

    @Test
    @DisplayName("FREE and an unavailable paid price are different on the wire, and neither carries 0")
    void freeIsNotAMissingPrice() {
        JsonNode free = summaryJson(course(CourseAccessType.FREE, null), List.of()).get("offer");
        JsonNode unavailable = summaryJson(course(CourseAccessType.PURCHASE, null), List.of()).get("offer");

        assertThat(free.get("accessType").asString()).isEqualTo("FREE");
        assertThat(free.get("pricingStatus").asString()).isEqualTo("FREE");
        assertThat(free.get("currency").isNull()).isTrue();
        assertThat(free.get("purchasePrice").isNull()).isTrue();
        assertThat(free.get("plans").isEmpty()).isTrue();

        assertThat(unavailable.get("accessType").asString()).isEqualTo("PURCHASE");
        assertThat(unavailable.get("pricingStatus").asString()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.get("currency").asString()).isEqualTo("EGP");
        assertThat(unavailable.get("purchasePrice").isNull())
                .as("a missing price is sent as an explicit null, not dropped and not 0")
                .isTrue();
    }

    @Test
    @DisplayName("a purchase price is an EGP decimal with two places")
    void purchasePriceOnTheWire() {
        String body = json.writeValueAsString(
                mapper.toSummary(course(CourseAccessType.PURCHASE, "450"),
                        PublicOffer.of(course(CourseAccessType.PURCHASE, "450"), List.of())));

        assertThat(body).contains("\"currency\":\"EGP\"").contains("\"purchasePrice\":450.00");
    }

    @Test
    @DisplayName("a subscription with no sellable plan is UNAVAILABLE with an empty plan list")
    void unavailableSubscriptionOnTheWire() {
        JsonNode offer = summaryJson(course(CourseAccessType.SUBSCRIPTION, null),
                List.of(plan(9, "Zero", 0, 1, SubscriptionUnit.MONTH, "0"))).get("offer");

        assertThat(offer.get("pricingStatus").asString()).isEqualTo("UNAVAILABLE");
        assertThat(offer.get("currency").asString()).isEqualTo("EGP");
        assertThat(offer.get("purchasePrice").isNull()).isTrue();
        assertThat(offer.get("plans").isEmpty()).isTrue();
    }

    // ─── what must never be public ───────────────────────────────────────────

    @Test
    @DisplayName("no publication state, counts, internal ids, or instructor account data are serialized")
    void nothingPrivateIsSerialized() {
        var course = course(CourseAccessType.SUBSCRIPTION, null);
        var offer = PublicOffer.of(course, List.of(plan(9, "Monthly", 0, 1, SubscriptionUnit.MONTH, "120")));
        String body = json.writeValueAsString(mapper.toDetail(course, offer))
                + json.writeValueAsString(mapper.toSummary(course, offer));

        assertThat(body)
                .doesNotContain(INSTRUCTOR_EMAIL)
                .doesNotContain("secret-password")
                .doesNotContain(INSTRUCTOR_BIO)
                .doesNotContain("\"status\"")
                .doesNotContain("\"visibility\"")
                .doesNotContain("studentsCount")
                .doesNotContain("987")
                .doesNotContain("revision")
                .doesNotContain("instructorId")
                .doesNotContain("\"lessons\"")
                .doesNotContain("videoUrl")
                .doesNotContain("orderIndex")
                .doesNotContain("retiredAt");
    }

    // ─── field rules ─────────────────────────────────────────────────────────

    @ParameterizedTest(name = "kept: {0}")
    @ValueSource(strings = {
            "/uploads/3f2a9c1e-8d7b-4c55-9e1f-0a1b2c3d4e5f.webp",
            "https://images.example.com/covers/algebra.png"})
    void safeImagesAreKept(String image) {
        assertThat(PublicCourseMapper.safeImageUrl(image)).isEqualTo(image);
    }

    @ParameterizedTest(name = "dropped: {0}")
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "javascript:alert(1)",
            "data:image/png;base64,iVBORw0KGgo=",
            "http://images.example.com/cover.png",
            "//evil.example.com/cover.png",
            "/uploads/../application.properties",
            "/uploads/a/../../etc/passwd",
            "/api/v1/student/courses",
            "https://user:password@images.example.com/cover.png",
            "https:///no-host",
            "not a url"})
    void unsafeImagesAreDropped(String image) {
        assertThat(PublicCourseMapper.safeImageUrl(image)).isNull();
    }

    @Test
    @DisplayName("an unknown duration is null, never 0; a blank subtitle or instructor name is null")
    void unknownValuesAreNull() {
        var course = course(CourseAccessType.FREE, null);
        course.setDuration(0);
        course.setSubtitle("  ");
        course.getInstructor().getUser().setFullName(" ");

        JsonNode node = summaryJson(course, List.of());

        assertThat(node.get("durationSeconds").isNull()).isTrue();
        assertThat(node.get("subtitle").isNull()).isTrue();
        assertThat(node.get("instructorName").isNull()).isTrue();
    }
}
