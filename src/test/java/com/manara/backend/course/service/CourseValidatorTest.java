package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.service.QuizValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseValidatorTest {

    private static final java.util.function.IntSupplier NO_PERSISTED_LESSONS = () -> 0;

    private final CourseValidator validator = new CourseValidator(new QuizValidator());

    // --- structure ----------------------------------------------------------

    @Test
    void defaultsToFlatSoCoursesWrittenBeforeStructureExistedKeepBehaving() {
        var settings = validator.resolveAndValidate(course().build(), null, NO_PERSISTED_LESSONS);

        assertThat(settings.structure()).isEqualTo(CourseStructure.FLAT);
        assertThat(settings.status()).isEqualTo(CourseStatus.DRAFT);
    }

    @Test
    void acceptsAFlatCourseWithLessons() {
        var request = course()
                .structure(CourseStructure.FLAT)
                .lessons(List.of(lesson()))
                .status(CourseStatus.PUBLISHED)
                .build();

        assertThat(validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS).structure())
                .isEqualTo(CourseStructure.FLAT);
    }

    @Test
    void acceptsAModuleCourseWithNestedLessons() {
        var request = course()
                .structure(CourseStructure.MODULES)
                .modules(List.of(module(lesson())))
                .status(CourseStatus.PUBLISHED)
                .build();

        assertThat(validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS).structure())
                .isEqualTo(CourseStructure.MODULES);
    }

    @Test
    void refusesToMixFlatLessonsWithModules() {
        var request = course()
                .structure(CourseStructure.FLAT)
                .modules(List.of(module(lesson())))
                .build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.flatWithModules");
    }

    @Test
    void refusesDirectLessonsOnAModuleCourse() {
        var request = course()
                .structure(CourseStructure.MODULES)
                .lessons(List.of(lesson()))
                .build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.modulesWithLessons");
    }

    @Test
    void refusesAStructureSwitchThatCarriesNoContentToMoveInto() {
        Course existing = persistedCourse(CourseStructure.FLAT, CourseAccessType.FREE);
        var request = course().structure(CourseStructure.MODULES).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, existing, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.structureChangeRequiresContent");
    }

    // --- publishing ---------------------------------------------------------

    @Test
    void refusesToPublishACourseWithoutLessons() {
        var request = course().status(CourseStatus.PUBLISHED).lessons(List.of()).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.publishRequiresLesson");
    }

    @Test
    void refusesToPublishAModuleCourseWhoseModulesAreAllEmpty() {
        var request = course()
                .structure(CourseStructure.MODULES)
                .status(CourseStatus.PUBLISHED)
                .modules(List.of(module()))
                .build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.publishRequiresLesson");
    }

    @Test
    void checksAlreadyStoredLessonsWhenPublishingWithoutSendingContent() {
        Course existing = persistedCourse(CourseStructure.FLAT, CourseAccessType.FREE);
        var request = course().status(CourseStatus.PUBLISHED).build();

        assertThat(validator.resolveAndValidate(request, existing, () -> 3).status())
                .isEqualTo(CourseStatus.PUBLISHED);

        assertThatThrownBy(() -> validator.resolveAndValidate(request, existing, () -> 0))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.publishRequiresLesson");
    }

    // --- content ------------------------------------------------------------

    @Test
    void refusesAModuleWithoutATitle() {
        ModuleRequest module = module(lesson());
        module.setTitle(" ");
        var request = course().structure(CourseStructure.MODULES).modules(List.of(module)).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.moduleTitleRequired");
    }

    @Test
    void refusesALessonWithoutAVideo() {
        LessonRequest lesson = lesson();
        lesson.setVideoUrl(null);
        var request = course().lessons(List.of(lesson)).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.lessonVideoUrlRequired");
    }

    @Test
    void appliesTheSameQuizRulesToALessonQuizAsToAModuleExam() {
        QuizRequest brokenQuiz = quiz();
        brokenQuiz.setPassingScore(0);

        LessonRequest lesson = lesson();
        lesson.setQuiz(brokenQuiz);
        var flatRequest = course().lessons(List.of(lesson)).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(flatRequest, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.passingScoreRange");

        ModuleRequest module = module(lesson());
        module.setQuiz(brokenQuiz);
        var moduleRequest = course()
                .structure(CourseStructure.MODULES)
                .modules(List.of(module))
                .build();

        assertThatThrownBy(() -> validator.resolveAndValidate(moduleRequest, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.passingScoreRange");
    }

    @Test
    void validatesTheFinalExamToo() {
        QuizRequest brokenQuiz = quiz();
        brokenQuiz.getQuestions().getFirst().setCorrectOptionId("not-an-option");
        var request = course().lessons(List.of(lesson())).finalQuiz(brokenQuiz).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.correctOptionUnknown");
    }

    // --- pricing ------------------------------------------------------------

    @Test
    void keepsFreeCoursesFreeAndStoresNoPrice() {
        var request = course().accessType(CourseAccessType.FREE).purchasePrice(new BigDecimal("30")).build();

        var settings = validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS);

        assertThat(settings.accessType()).isEqualTo(CourseAccessType.FREE);
        assertThat(settings.purchasePrice()).isNull();
    }

    @Test
    void refusesAPurchaseCourseWithoutAPositivePrice() {
        var request = course().accessType(CourseAccessType.PURCHASE).purchasePrice(BigDecimal.ZERO).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.purchasePriceRequired");
    }

    @Test
    void infersAccessTypeFromTheLegacyPriceFieldSoOlderClientsKeepWorking() {
        var paid = course().price(new BigDecimal("49.99")).build();
        var freeSettings = validator.resolveAndValidate(course().price(BigDecimal.ZERO).build(), null, NO_PERSISTED_LESSONS);
        var paidSettings = validator.resolveAndValidate(paid, null, NO_PERSISTED_LESSONS);

        assertThat(paidSettings.accessType()).isEqualTo(CourseAccessType.PURCHASE);
        assertThat(paidSettings.purchasePrice()).isEqualByComparingTo("49.99");
        assertThat(freeSettings.accessType()).isEqualTo(CourseAccessType.FREE);
    }

    @Test
    void refusesASubscriptionCourseWithoutPlans() {
        var request = course().accessType(CourseAccessType.SUBSCRIPTION).build();

        assertThatThrownBy(() -> validator.resolveAndValidate(request, null, NO_PERSISTED_LESSONS))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.subscriptionPlansRequired");
    }

    @Test
    void refusesSubscriptionPlansWithNonPositiveDurationOrPrice() {
        var zeroDuration = course()
                .accessType(CourseAccessType.SUBSCRIPTION)
                .subscriptionPlans(List.of(plan(0, new BigDecimal("100"))))
                .build();
        var zeroPrice = course()
                .accessType(CourseAccessType.SUBSCRIPTION)
                .subscriptionPlans(List.of(plan(1, BigDecimal.ZERO)))
                .build();

        assertThatThrownBy(() -> validator.resolveAndValidate(zeroDuration, null, NO_PERSISTED_LESSONS))
                .hasMessage("error.course.planDurationPositive");
        assertThatThrownBy(() -> validator.resolveAndValidate(zeroPrice, null, NO_PERSISTED_LESSONS))
                .hasMessage("error.course.planPricePositive");
    }

    @Test
    void aSubscriptionCourseThatSaysNothingAboutPlansKeepsTheOnesItHas() {
        Course existing = persistedCourse(CourseStructure.FLAT, CourseAccessType.SUBSCRIPTION);
        var request = course().build();

        var settings = validator.resolveAndValidate(request, existing, NO_PERSISTED_LESSONS);

        assertThat(settings.accessType()).isEqualTo(CourseAccessType.SUBSCRIPTION);
        assertThat(settings.purchasePrice()).isNull();
    }

    // --- fixtures -----------------------------------------------------------

    private static CourseRequest.CourseRequestBuilder course() {
        return CourseRequest.builder().title("Course").description("Description");
    }

    private static Course persistedCourse(CourseStructure structure, CourseAccessType accessType) {
        return Course.builder()
                .id(1L)
                .title("Course")
                .structure(structure)
                .status(CourseStatus.DRAFT)
                .accessType(accessType)
                .build();
    }

    private static LessonRequest lesson() {
        return LessonRequest.builder()
                .title("Lesson")
                .videoUrl("https://youtube.com/watch?v=abc")
                .build();
    }

    private static ModuleRequest module(LessonRequest... lessons) {
        return ModuleRequest.builder()
                .title("Module")
                .lessons(List.of(lessons))
                .build();
    }

    private static SubscriptionPlanRequest plan(int duration, BigDecimal price) {
        return SubscriptionPlanRequest.builder()
                .name("Monthly")
                .duration(duration)
                .unit(SubscriptionUnit.MONTH)
                .price(price)
                .build();
    }

    private static QuizRequest quiz() {
        return QuizRequest.builder()
                .title("Quiz")
                .passingScore(70)
                .questions(new java.util.ArrayList<>(List.of(QuizQuestionRequest.builder()
                        .text("Question")
                        .correctOptionId("a")
                        .options(List.of(
                                QuizOptionRequest.builder().id("a").text("A").build(),
                                QuizOptionRequest.builder().id("b").text("B").build()))
                        .build())))
                .build();
    }
}
