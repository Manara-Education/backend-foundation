package com.manara.backend.course.integration;

import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.InstructorModuleResponse;
import com.manara.backend.course.dto.LessonOrderRequest;
import com.manara.backend.course.dto.ModuleOrderRequest;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.dto.SubscriptionPlanResponse;
import com.manara.backend.quiz.dto.InstructorQuizQuestionResponse;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizOptionResponse;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/** Builders for the course-authoring integration tests. */
final class CourseAuthoringFixtures {

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private CourseAuthoringFixtures() {
    }

    static User user(Role role) {
        long n = SEQUENCE.incrementAndGet();
        return User.builder()
                .fullName(role + " " + n)
                .email(role.name().toLowerCase() + n + "@manara.test")
                .password("{noop}irrelevant")
                .emailVerified(true)
                .role(role)
                .build();
    }

    static Instructor instructor(User user) {
        return Instructor.builder().user(user).bio("bio").specialization("spec").build();
    }

    static Student student(User user) {
        return Student.builder().user(user).build();
    }

    static LessonRequest lesson(String title) {
        return LessonRequest.builder()
                .title(title)
                .description(title + " description")
                .videoUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
                .build();
    }

    /**
     * A quiz with two questions of three options each, the first option of each being correct.
     *
     * <p>Deliberately not the minimum. A one-question, two-option quiz cannot tell a reorder from
     * a no-op, and cannot express "delete an option" at all without emptying the question — so a
     * matrix built on one would report coverage it does not have.
     *
     * <p>Client-supplied ids are how the aggregate save tells "edit this question" from "add one",
     * and the answer key is an option id — so the fixture mints its own and points {@code
     * correctOptionId} at one of them, exactly as the editor does for a quiz being written.
     */
    static QuizRequest quiz(String title) {
        return QuizRequest.builder()
                .title(title)
                .instructions(title + " instructions")
                .passingScore(60)
                .questions(List.of(question(title + " question one"), question(title + " question two")))
                .build();
    }

    private static QuizQuestionRequest question(String text) {
        String right = "opt-" + SEQUENCE.incrementAndGet();
        String wrong = "opt-" + SEQUENCE.incrementAndGet();
        String alsoWrong = "opt-" + SEQUENCE.incrementAndGet();
        return QuizQuestionRequest.builder()
                .id("q-" + SEQUENCE.incrementAndGet())
                .text(text)
                .explanation(text + " explanation")
                .hintByAiEnabled(false)
                .correctOptionId(right)
                .options(List.of(
                        QuizOptionRequest.builder().id(right).text("Right").build(),
                        QuizOptionRequest.builder().id(wrong).text("Wrong").build(),
                        QuizOptionRequest.builder().id(alsoWrong).text("Also wrong").build()))
                .build();
    }

    static SubscriptionPlanRequest plan(String name, int duration, SubscriptionUnit unit, String price) {
        return SubscriptionPlanRequest.builder()
                .name(name)
                .duration(duration)
                .unit(unit)
                .price(new java.math.BigDecimal(price))
                .build();
    }

    static ModuleRequest module(String title, LessonRequest... lessons) {
        return ModuleRequest.builder()
                .title(title)
                .description(title + " description")
                .lessons(List.of(lessons))
                .build();
    }

    static CourseRequest modularCourse(String title, CourseStatus status, ModuleRequest... modules) {
        return CourseRequest.builder()
                .title(title)
                .description(title + " description, long enough to be meaningful")
                .structure(CourseStructure.MODULES)
                .modules(List.of(modules))
                .status(status)
                .build();
    }

    static CourseRequest flatCourse(String title, CourseStatus status, LessonRequest... lessons) {
        return CourseRequest.builder()
                .title(title)
                .description(title + " description, long enough to be meaningful")
                .structure(CourseStructure.FLAT)
                .lessons(List.of(lessons))
                .status(status)
                .build();
    }

    /**
     * The payload the editor sends back after loading a course: the same tree, with every id it was
     * given. Building requests this way is what makes an "unchanged save" genuinely unchanged, and
     * it is the shape every real edit is a small deviation from.
     */
    static CourseRequest echoOf(InstructorCourseResponse course) {
        return CourseRequest.builder()
                .title(course.getTitle())
                .subtitle(course.getSubtitle())
                .description(course.getDescription())
                .image(course.getImage())
                .structure(course.getStructure())
                .accessType(course.getAccessType())
                .purchasePrice(course.getPurchasePrice())
                .modules(course.getStructure() == CourseStructure.MODULES
                        ? course.getModules().stream().map(CourseAuthoringFixtures::echoOf).toList()
                        : null)
                .lessons(course.getStructure() == CourseStructure.MODULES
                        ? null
                        : course.getLessons().stream().map(CourseAuthoringFixtures::echoOf).toList())
                .finalQuiz(echoOf(course.getFinalQuiz()))
                .subscriptionPlans(course.getSubscriptionPlans() == null
                        ? null
                        : course.getSubscriptionPlans().stream().map(CourseAuthoringFixtures::echoOf).toList())
                .build();
    }

    static ModuleRequest echoOf(InstructorModuleResponse module) {
        return ModuleRequest.builder()
                .id(module.getId())
                .title(module.getTitle())
                .description(module.getDescription())
                .lessons(module.getLessons().stream().map(CourseAuthoringFixtures::echoOf).toList())
                .quiz(echoOf(module.getQuiz()))
                .build();
    }

    static LessonRequest echoOf(InstructorLessonResponse lesson) {
        return LessonRequest.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoUrl(lesson.getVideoUrl())
                .quiz(echoOf(lesson.getQuiz()))
                .build();
    }

    /**
     * A quiz echoed back with every id intact — including each option's, which is what the answer
     * key points at. An echo that dropped the quiz would not be an echo: the aggregate save reads
     * an absent quiz as "delete it", so a fixture that forgot one would quietly test deletion.
     */
    static QuizRequest echoOf(InstructorQuizResponse quiz) {
        if (quiz == null) {
            return null;
        }
        return QuizRequest.builder()
                .id(quiz.getId())
                .title(quiz.getTitle())
                .instructions(quiz.getInstructions())
                .passingScore(quiz.getPassingScore())
                .questions(quiz.getQuestions().stream().map(CourseAuthoringFixtures::echoOf).toList())
                .build();
    }

    static QuizQuestionRequest echoOf(InstructorQuizQuestionResponse question) {
        return QuizQuestionRequest.builder()
                .id(question.getId())
                .text(question.getText())
                .correctOptionId(question.getCorrectOptionId())
                .explanation(question.getExplanation())
                .hintByAiEnabled(question.getHintByAiEnabled())
                .options(question.getOptions().stream().map(CourseAuthoringFixtures::echoOf).toList())
                .build();
    }

    static QuizOptionRequest echoOf(QuizOptionResponse option) {
        return QuizOptionRequest.builder()
                .id(option.getId())
                .text(option.getText())
                .build();
    }

    static SubscriptionPlanRequest echoOf(SubscriptionPlanResponse plan) {
        return SubscriptionPlanRequest.builder()
                .id(plan.getId())
                .name(plan.getName())
                .duration(plan.getDuration())
                .unit(plan.getUnit())
                .price(plan.getPrice())
                .build();
    }

    /** The same payload with its modules put in the given order. */
    static CourseRequest withModuleOrder(CourseRequest request, List<Long> moduleIds) {
        Map<Long, ModuleRequest> byId = request.getModules().stream()
                .collect(Collectors.toMap(ModuleRequest::getId, m -> m));
        request.setModules(moduleIds.stream().map(byId::get).toList());
        return request;
    }

    static ModuleOrderRequest order(List<Long> moduleIds) {
        return new ModuleOrderRequest(moduleIds);
    }

    static LessonOrderRequest lessonOrder(List<Long> lessonIds) {
        return new LessonOrderRequest(lessonIds);
    }
}
