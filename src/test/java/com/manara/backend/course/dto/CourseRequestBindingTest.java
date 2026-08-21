package com.manara.backend.course.dto;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Binds the documented course payload verbatim.
 *
 * <p>The contract writes enums in lowercase and orders in a field called {@code order}, while the
 * codebase uses uppercase constants and {@code orderIndex}. That translation happens in
 * {@code @JsonCreator} and {@code @JsonAlias} annotations, which nothing else exercises — a
 * mistake there would otherwise only show up against a running server.
 */
class CourseRequestBindingTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
                .isInstanceOf(JsonMappingException.class);
    }

    /** Small helper so the option assertion reads as an order comparison. */
    private interface QuizOptionOrder {
        static Integer of(com.manara.backend.quiz.dto.QuizOptionRequest option) {
            return option.getOrderIndex();
        }
    }
}
