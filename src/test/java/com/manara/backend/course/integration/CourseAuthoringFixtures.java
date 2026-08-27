package com.manara.backend.course.integration;

import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.InstructorModuleResponse;
import com.manara.backend.course.dto.ModuleOrderRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
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
                .build();
    }

    static ModuleRequest echoOf(InstructorModuleResponse module) {
        return ModuleRequest.builder()
                .id(module.getId())
                .title(module.getTitle())
                .description(module.getDescription())
                .lessons(module.getLessons().stream().map(CourseAuthoringFixtures::echoOf).toList())
                .build();
    }

    static LessonRequest echoOf(InstructorLessonResponse lesson) {
        return LessonRequest.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoUrl(lesson.getVideoUrl())
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
}
