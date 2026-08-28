package com.manara.backend.course.dto;

import com.manara.backend.common.json.Patch;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionUnit;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds the documented course payload verbatim, through the Jackson the server actually uses.
 *
 * <p>The contract writes enums in lowercase and orders in a field called {@code order}, while the
 * codebase uses uppercase constants and {@code orderIndex}. That translation happens in
 * {@code @JsonCreator} and {@code @JsonAlias} annotations, which nothing else exercises — a
 * mistake there would otherwise only show up against a running server.
 *
 * <h2>Which Jackson</h2>
 * {@code tools.jackson}, deliberately and explicitly. This class used to instantiate
 * {@code com.fasterxml.jackson.databind.ObjectMapper} — Jackson 2, which is on the classpath only
 * because other libraries drag it in — while Spring Boot 4 binds request bodies with Jackson 3.
 * The two disagree about how to construct this DTO, and the disagreement was not academic: under
 * Jackson 3 {@code subtitle} and {@code image} were silently discarded on every real HTTP update,
 * and this file passed throughout. A binding test that binds with a different library than the
 * server is not evidence about the server.
 *
 * <p>{@link com.manara.backend.course.integration.CourseAggregateHttpContractTest} closes the same
 * gap from the other end, over MockMvc against a live context.
 */
class CourseRequestBindingTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private static final String MODULE_COURSE_PAYLOAD = """
            {
              "title": "Course Title",
              "description": "Course Description",
              "image": "image-url",
              "structure": "modules",
              "lessons": [],
              "modules": [
                {
                  "id": 4,
                  "title": "Module 1",
                  "description": "Module Description",
                  "order": 0,
                  "lessons": [
                    {
                      "id": 9,
                      "title": "Lesson 1",
                      "description": "Lesson description",
                      "videoUrl": "https://youtube.com/watch?v=abc",
                      "order": 0,
                      "quiz": {
                        "title": "Lesson Quiz",
                        "passingScore": 70,
                        "questions": [
                          {
                            "text": "Question text",
                            "correctOptionId": "option-2",
                            "explanation": "Explanation of the answer",
                            "hintByAiEnabled": true,
                            "order": 0,
                            "options": [
                              { "id": "option-1", "text": "Answer 1", "order": 0 },
                              { "id": "option-2", "text": "Answer 2", "order": 1 }
                            ]
                          }
                        ]
                      }
                    }
                  ],
                  "quiz": { "title": "Module Exam", "passingScore": 70, "questions": [] }
                }
              ],
              "finalQuiz": { "title": "Final Exam", "passingScore": 70, "questions": [] },
              "accessType": "subscription",
              "purchasePrice": null,
              "subscriptionPlans": [
                { "id": 3, "name": "Monthly", "duration": 1, "unit": "month", "price": 100 }
              ],
              "status": "draft"
            }
            """;

    @Test
    void bindsTheDocumentedModuleCoursePayload() throws Exception {
        CourseRequest request = objectMapper.readValue(MODULE_COURSE_PAYLOAD, CourseRequest.class);

        assertThat(request.getStructure()).isEqualTo(CourseStructure.MODULES);
        assertThat(request.getAccessType()).isEqualTo(CourseAccessType.SUBSCRIPTION);
        assertThat(request.getStatus()).isEqualTo(CourseStatus.DRAFT);
        assertThat(request.getFinalQuiz().getTitle()).isEqualTo("Final Exam");

        // An explicit empty array is not the same as an absent one: it says "no flat lessons".
        assertThat(request.getLessons()).isEmpty();
        assertThat(request.carriesContentFor(CourseStructure.MODULES)).isTrue();
    }

    @Test
    void readsOrderIntoTheStoredOrderIndexAtEveryLevel() throws Exception {
        CourseRequest request = objectMapper.readValue(MODULE_COURSE_PAYLOAD, CourseRequest.class);

        var lesson = request.getModules().getFirst().getLessons().getFirst();
        var question = lesson.getQuiz().getQuestions().getFirst();

        assertThat(lesson.getOrderIndex()).isZero();
        assertThat(question.getOrderIndex()).isZero();
        assertThat(question.getOptions()).extracting(QuizOptionOrder::of).containsExactly(0, 1);
    }

    @Test
    void keepsClientGeneratedOptionReferencesIntactSoTheAnswerKeyResolves() throws Exception {
        CourseRequest request = objectMapper.readValue(MODULE_COURSE_PAYLOAD, CourseRequest.class);

        var question = request.getModules().getFirst().getLessons().getFirst().getQuiz().getQuestions().getFirst();

        assertThat(question.getCorrectOptionId()).isEqualTo("option-2");
        assertThat(question.getOptions()).extracting(o -> o.getId()).containsExactly("option-1", "option-2");
        assertThat(question.getHintByAiEnabled()).isTrue();
    }

    @Test
    void bindsNestedServerIdsAsNumbers() throws Exception {
        CourseRequest request = objectMapper.readValue(MODULE_COURSE_PAYLOAD, CourseRequest.class);

        assertThat(request.getModules().getFirst().getId()).isEqualTo(4L);
        assertThat(request.getModules().getFirst().getLessons().getFirst().getId()).isEqualTo(9L);
        assertThat(request.getSubscriptionPlans().getFirst().getId()).isEqualTo(3L);
        assertThat(request.getSubscriptionPlans().getFirst().getUnit()).isEqualTo(SubscriptionUnit.MONTH);
    }

    @Test
    void stillAcceptsTheLegacyPriceOnlyPayload() throws Exception {
        String legacy = """
                { "title": "Course", "description": "Description", "price": 49.99 }
                """;

        CourseRequest request = objectMapper.readValue(legacy, CourseRequest.class);

        assertThat(request.getAccessType()).isNull();
        assertThat(request.resolvePurchasePrice()).isEqualByComparingTo("49.99");
        // Nothing was said about content, so nothing may be touched.
        assertThat(request.carriesContentFor(CourseStructure.FLAT)).isFalse();
    }

    @Test
    void prefersPurchasePriceOverTheLegacyName() throws Exception {
        String both = """
                { "title": "Course", "description": "D", "price": 10, "purchasePrice": 25 }
                """;

        assertThat(objectMapper.readValue(both, CourseRequest.class).resolvePurchasePrice())
                .isEqualByComparingTo("25");
    }

    @Test
    void acceptsEnumsInAnyCase() throws Exception {
        String upper = """
                { "title": "C", "description": "D", "structure": "MODULES", "status": "PUBLISHED" }
                """;

        CourseRequest request = objectMapper.readValue(upper, CourseRequest.class);

        assertThat(request.getStructure()).isEqualTo(CourseStructure.MODULES);
        assertThat(request.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
    }

    @Test
    void rejectsAnUnsupportedEnumValueInsteadOfSilentlyIgnoringIt() {
        String invalid = """
                { "title": "C", "description": "D", "structure": "chapters" }
                """;

        // Surfaces as an unreadable body, which GlobalExceptionHandler turns into a localized 400.
        assertThatThrownBy(() -> objectMapper.readValue(invalid, CourseRequest.class))
                .isInstanceOf(DatabindException.class);
    }

    /**
     * The three states an optional metadata field can be in, and the two that used to look alike.
     *
     * <p>A Java bean has no way to tell "the client said null" from "the client said nothing", and
     * for {@code subtitle} and {@code image} the two mean opposite things: clear the value, or
     * leave it exactly as it is. It was tracked in the setters, which Jackson 3 never calls for
     * this DTO — so every field read as absent and neither could be written at all.
     */
    @Nested
    class PresenceOfOptionalMetadata {

        @Test
        void anOmittedFieldIsAbsent() throws Exception {
            CourseRequest request = objectMapper.readValue(
                    """
                    { "title": "C", "description": "D" }
                    """, CourseRequest.class);

            assertThat(request.carriesSubtitle()).isFalse();
            assertThat(request.carriesImage()).isFalse();
            assertThat(request.subtitleValue()).isNull();
            assertThat(request.imageValue()).isNull();
        }

        @Test
        void aFieldWithAValueIsPresentWithIt() throws Exception {
            CourseRequest request = objectMapper.readValue(
                    """
                    { "title": "C", "description": "D",
                      "subtitle": "New subtitle", "image": "/uploads/new.png" }
                    """, CourseRequest.class);

            assertThat(request.carriesSubtitle()).isTrue();
            assertThat(request.subtitleValue()).isEqualTo("New subtitle");
            assertThat(request.carriesImage()).isTrue();
            assertThat(request.imageValue()).isEqualTo("/uploads/new.png");
        }

        @Test
        void anExplicitNullIsPresentAndMeansClearIt() throws Exception {
            CourseRequest request = objectMapper.readValue(
                    """
                    { "title": "C", "description": "D", "subtitle": null, "image": null }
                    """, CourseRequest.class);

            assertThat(request.carriesSubtitle()).isTrue();
            assertThat(request.subtitleValue()).isNull();
            assertThat(request.carriesImage()).isTrue();
            assertThat(request.imageValue()).isNull();
        }

        @Test
        void oneFieldPresentDoesNotMakeTheOtherPresent() throws Exception {
            CourseRequest request = objectMapper.readValue(
                    """
                    { "title": "C", "description": "D", "subtitle": "Only this one" }
                    """, CourseRequest.class);

            assertThat(request.carriesSubtitle()).isTrue();
            assertThat(request.carriesImage()).isFalse();
        }

        /**
         * The bookkeeping is the server's, and a client cannot reach it.
         *
         * <p>Under the previous design Jackson 3 bound the private {@code presentFields} set
         * straight off the wire, so a payload could name which fields the server should believe it
         * had mentioned. There is no such field any more; this asserts the contract has no way back
         * in by that name, and that a payload carrying it is still read normally.
         */
        @Test
        void internalPresenceBookkeepingIsNotPartOfTheContract() throws Exception {
            CourseRequest request = objectMapper.readValue(
                    """
                    { "title": "C", "description": "D", "presentFields": ["SUBTITLE", "IMAGE"] }
                    """, CourseRequest.class);

            assertThat(request.carriesSubtitle()).isFalse();
            assertThat(request.carriesImage()).isFalse();
        }

        /** A request built in Java says the same three things, so tests exercise the real rule. */
        @Test
        void aJavaBuiltRequestExpressesTheSameThreeStates() {
            assertThat(CourseRequest.builder().build().carriesSubtitle()).isFalse();
            assertThat(CourseRequest.builder().subtitle("x").build().subtitleValue()).isEqualTo("x");

            CourseRequest cleared = CourseRequest.builder().subtitle(null).build();
            assertThat(cleared.carriesSubtitle()).isTrue();
            assertThat(cleared.subtitleValue()).isNull();

            CourseRequest viaSetter = new CourseRequest();
            viaSetter.setImage(Patch.of("/uploads/x.png"));
            assertThat(viaSetter.carriesImage()).isTrue();
            assertThat(viaSetter.imageValue()).isEqualTo("/uploads/x.png");
        }
    }

    /** Small helper so the option assertion reads as an order comparison. */
    private interface QuizOptionOrder {
        static Integer of(com.manara.backend.quiz.dto.QuizOptionRequest option) {
            return option.getOrderIndex();
        }
    }
}
