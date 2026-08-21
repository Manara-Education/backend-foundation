package com.manara.backend.course.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseProgressionCalculator;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.quiz.mapper.QuizMapper;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.model.QuizQuestion;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The answer key is the one thing a learner response must never carry. This asserts it on the
 * serialized JSON rather than on the DTO, because that is what actually reaches a browser — a field
 * added to a nested type later would show up here.
 */
class CourseAggregateMapperTest {

    private static final String EXPLANATION = "B is correct because of the rule";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CourseAggregateMapper mapper = buildMapper();

    /** An enrolled learner who has completed nothing — everything the first module opens is open. */
    private static CourseProgression enrolled(CourseAggregate aggregate) {
        return new CourseProgressionCalculator().compute(aggregate, Set.of(), Map.of());
    }

    private static CourseAggregateMapper buildMapper() {
        DurationFormatter durationFormatter = mock(DurationFormatter.class);
        when(durationFormatter.formatSeconds(org.mockito.ArgumentMatchers.any())).thenReturn("0s");
        return new CourseAggregateMapper(
                new LessonMapper(durationFormatter), new QuizMapper(), new SubscriptionPlanMapper(), durationFormatter);
    }

    @Test
    void theInstructorViewCarriesTheAnswerKeyAndTheExplanation() {
        var response = mapper.toInstructorCourseResponse(flatAggregate());

        var question = response.getLessons().getFirst().getQuiz().getQuestions().getFirst();
        assertThat(question.getCorrectOptionId()).isEqualTo("201");
        assertThat(question.getExplanation()).isEqualTo(EXPLANATION);
        assertThat(question.getOptions()).extracting(o -> o.getId()).containsExactly("200", "201");
    }

    @Test
    void theLearnerViewLeaksNoAnswerAnywhereInTheSerializedTree() throws Exception {
        var aggregate = flatAggregate();
        var response = mapper.toCourseDetailsResponse(aggregate, enrolled(aggregate), null);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json)
                .doesNotContain("correctOptionId")
                .doesNotContain("isCorrect")
                .doesNotContain(EXPLANATION);
        // The quiz itself is still there — learners need the questions to attempt it.
        assertThat(json).contains("Lesson Quiz").contains("Answer B");
    }

    @Test
    void theLearnerViewLeaksNoAnswerFromAModuleExamEither() throws Exception {
        var aggregate = modularAggregate();
        var response = mapper.toCourseDetailsResponse(aggregate, enrolled(aggregate), null);

        String json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("correctOptionId").doesNotContain(EXPLANATION);
        assertThat(response.getModules()).hasSize(1);
        assertThat(response.getModules().getFirst().getQuiz()).isNotNull();
    }

    @Test
    void courseDiscoveryListsTheCurriculumWithoutHandingOutAnyOfIt() {
        var aggregate = flatAggregate();

        var response = mapper.toCourseDetailsResponse(aggregate, CourseProgression.forVisitor(), null);

        var lesson = response.getLessons().getFirst();
        assertThat(lesson.getLocked()).isTrue();
        // The row a browsing visitor sees: what the lesson is, and how long it takes.
        assertThat(lesson.getTitle()).isNotBlank();
        assertThat(lesson.getVideoUrl()).isNull();
        assertThat(lesson.getDescription()).isNull();
        assertThat(lesson.getQuiz()).isNull();
        assertThat(response.getProgress()).isNull();
    }

    @Test
    void anEnrolledLearnerCarriesTheirOwnProgressionInTheResponse() {
        var aggregate = flatAggregate();

        var response = mapper.toCourseDetailsResponse(aggregate, enrolled(aggregate), null);

        assertThat(response.getLessons().getFirst().getLocked()).isFalse();
        assertThat(response.getProgress()).isZero();
        assertThat(response.getCourseCompleted()).isFalse();
        assertThat(response.getNextLessonId()).isNotNull();
    }

    @Test
    void aModuleTheLearnerHasNotReachedIsMarkedLocked() {
        var aggregate = modularAggregate();

        var response = mapper.toCourseDetailsResponse(aggregate, CourseProgression.forVisitor(), null);

        assertThat(response.getModules().getFirst().getLocked()).isTrue();
    }

    @Test
    void aFlatCourseReturnsItsLessonsAndNoModules() {
        var response = mapper.toInstructorCourseResponse(flatAggregate());

        assertThat(response.getStructure()).isEqualTo(CourseStructure.FLAT);
        assertThat(response.getLessons()).hasSize(1);
        assertThat(response.getModules()).isEmpty();
    }

    @Test
    void aModuleCourseReturnsItsModulesAndNoTopLevelLessons() {
        var response = mapper.toInstructorCourseResponse(modularAggregate());

        assertThat(response.getStructure()).isEqualTo(CourseStructure.MODULES);
        assertThat(response.getModules()).hasSize(1);
        assertThat(response.getModules().getFirst().getLessons()).hasSize(1);
        // The tree is reachable through the modules; the flat branch stays empty so no client can
        // see a course as being both shapes at once.
        assertThat(response.getLessons()).isEmpty();
    }

    @Test
    void keepsTheLegacyPriceFieldInSyncWithThePurchasePrice() {
        var response = mapper.toInstructorCourseResponse(flatAggregate());

        assertThat(response.getPurchasePrice()).isEqualByComparingTo("49.99");
        assertThat(response.getPrice()).isEqualByComparingTo("49.99");
    }

    // --- fixtures -----------------------------------------------------------

    private CourseAggregate flatAggregate() {
        Course course = course(CourseStructure.FLAT);
        Lesson lesson = lesson(100L, course, null);

        return new CourseAggregate(
                course,
                List.of(),
                List.of(lesson),
                Map.of(lesson.getId(), quiz(QuizOwnerType.LESSON, lesson.getId())),
                Map.of(),
                null,
                List.of());
    }

    private CourseAggregate modularAggregate() {
        Course course = course(CourseStructure.MODULES);
        CourseModule module = CourseModule.builder()
                .id(50L).title("Module 1").orderIndex(0).course(course).build();
        Lesson lesson = lesson(100L, course, module);

        return new CourseAggregate(
                course,
                List.of(module),
                List.of(lesson),
                Map.of(lesson.getId(), quiz(QuizOwnerType.LESSON, lesson.getId())),
                Map.of(module.getId(), quiz(QuizOwnerType.MODULE, module.getId())),
                quiz(QuizOwnerType.COURSE, course.getId()),
                List.of());
    }

    private Course course(CourseStructure structure) {
        User user = User.builder().id(1L).fullName("Instructor").email("i@manara.com").role(Role.INSTRUCTOR).build();
        Instructor instructor = Instructor.builder().id(2L).user(user).bio("bio").specialization("Arabic").build();

        return Course.builder()
                .id(1L)
                .title("Course")
                .description("Description")
                .structure(structure)
                .status(CourseStatus.PUBLISHED)
                .accessType(CourseAccessType.PURCHASE)
                .purchasePrice(new java.math.BigDecimal("49.99"))
                .studentsCount(0)
                .instructor(instructor)
                .build();
    }

    private Lesson lesson(Long id, Course course, CourseModule module) {
        return Lesson.builder()
                .id(id)
                .title("Lesson")
                .videoUrl("https://youtube.com/watch?v=abc")
                .duration(60)
                .orderIndex(0)
                .course(course)
                .module(module)
                .build();
    }

    private Quiz quiz(QuizOwnerType ownerType, Long ownerId) {
        Quiz quiz = Quiz.builder()
                .id(300L)
                .ownerType(ownerType)
                .ownerId(ownerId)
                .title("Lesson Quiz")
                .passingScore(70)
                .questions(new ArrayList<>())
                .build();

        QuizQuestion question = QuizQuestion.builder()
                .id(400L)
                .text("Which answer is right?")
                .explanation(EXPLANATION)
                .hintByAiEnabled(true)
                .orderIndex(0)
                .options(new ArrayList<>())
                .build();
        question.addOption(QuizOption.builder().id(200L).text("Answer A").correct(false).orderIndex(0).build());
        question.addOption(QuizOption.builder().id(201L).text("Answer B").correct(true).orderIndex(1).build());

        quiz.addQuestion(question);
        return quiz;
    }
}
