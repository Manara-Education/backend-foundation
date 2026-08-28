package com.manara.backend.course.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.LessonOrderRequest;
import com.manara.backend.course.dto.ModuleOrderRequest;
import com.manara.backend.course.service.CourseService;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Course authoring. Create, read and update all speak the same full aggregate — content tree,
 * exams, pricing and status in one payload — so the editor never has to stitch several calls
 * together, and no scoped endpoint can drift away from the shared domain rules.
 *
 * <p>Two kinds of operation sit beside that aggregate, and both are there because expressing them
 * through it would be wrong rather than merely inconvenient.
 *
 * <ul>
 *   <li><strong>Lifecycle</strong> — {@code publish} and {@code unpublish}. Whether a course is
 *       visible is not a property a content save should be able to change in passing, and a client
 *       holding a stale copy of the course must not be able to unpublish it by saving a lesson.
 *       Making it an operation of its own is what separates "I edited this" from "I published
 *       this".
 *   <li><strong>Order</strong> — three focused commands, one per ordered scope a course has:
 *       its modules, the root lessons of a flat course, and the lessons inside one module. Each
 *       carries ids and nothing else. Reordering through the aggregate means posting the whole
 *       course back, so one tab dragging a module would overwrite the title another tab had just
 *       changed. Because all three exist, the aggregate save no longer has to be the authority on
 *       order at all: it keeps the stored order of content it is merely editing, and these
 *       commands are the only way an instructor-initiated reorder is expressed.
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/instructor/courses")
@RequiredArgsConstructor
public class InstructorCourseController {

    private final CourseService courseService;

    /**
     * Every course on the platform, drafts and private courses included — for instructors and
     * administrators, and now enforced as such rather than merely documented.
     */
    @GetMapping
    public ApiResponse<List<CourseResponse>> getAllCourses(@AuthenticationPrincipal User user) {
        return ApiResponse.success(courseService.getAllCourses(user));
    }

    @GetMapping("/my-courses")
    public ApiResponse<List<CourseResponse>> getMyCourses(@AuthenticationPrincipal User user) {
        return ApiResponse.success(courseService.getMyCourses(user));
    }

    @GetMapping("/{courseId}")
    public ApiResponse<InstructorCourseResponse> getCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        return ApiResponse.success(courseService.getCourseForEditing(user, courseId));
    }

    @PostMapping
    public ApiResponse<InstructorCourseResponse> createCourse(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.createCourse(user, request));
    }

    /**
     * Edits a course, published or not.
     *
     * <p>A payload that omits {@code status} — which every content save from the editor now does —
     * leaves the publication state exactly as it was.
     */
    @PutMapping("/{courseId}")
    public ApiResponse<InstructorCourseResponse> updateCourse(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody CourseRequest request) {
        return ApiResponse.success(courseService.updateCourse(user, courseId, request));
    }

    /**
     * Makes the course visible to learners, and makes now its version baseline.
     *
     * <p>Idempotent, and re-publishing is meaningful: it is how an instructor says the course as it
     * currently stands is the version they stand behind, which is what clears the "Updated" state
     * their learners are seeing.
     */
    @PostMapping("/{courseId}/publish")
    public ApiResponse<InstructorCourseResponse> publish(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        return ApiResponse.success(courseService.publish(user, courseId));
    }

    /** Withdraws the course from the catalogue. Content and enrolled learners are untouched. */
    @PostMapping("/{courseId}/unpublish")
    public ApiResponse<InstructorCourseResponse> unpublish(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId) {
        return ApiResponse.success(courseService.unpublish(user, courseId));
    }

    /**
     * Rewrites the order of the course's modules.
     *
     * <p>The body is the course's module ids in their new order and nothing else; positions are
     * derived from the array. The list must name every module of the course exactly once, so a
     * reorder built from a stale module list is refused rather than half-applied.
     */
    @PatchMapping("/{courseId}/modules/order")
    public ApiResponse<InstructorCourseResponse> reorderModules(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody ModuleOrderRequest request) {
        return ApiResponse.success(courseService.reorderModules(user, courseId, request));
    }

    /**
     * Rewrites the order of a flat course's root lessons.
     *
     * <p>The scope is the lessons that sit under no module. A modular course has none, so the same
     * call against one is refused rather than quietly doing nothing.
     */
    @PatchMapping("/{courseId}/lessons/order")
    public ApiResponse<InstructorCourseResponse> reorderLessons(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @Valid @RequestBody LessonOrderRequest request) {
        return ApiResponse.success(courseService.reorderLessons(user, courseId, request));
    }

    /**
     * Rewrites the order of one module's lessons.
     *
     * <p>The module is named by the path, not the body, so this command can only ever arrange
     * siblings — a lesson cannot be moved into another module by reordering. Re-parenting is a
     * structural edit and goes through the aggregate save.
     */
    @PatchMapping("/{courseId}/modules/{moduleId}/lessons/order")
    public ApiResponse<InstructorCourseResponse> reorderModuleLessons(
            @AuthenticationPrincipal User user,
            @PathVariable Long courseId,
            @PathVariable Long moduleId,
            @Valid @RequestBody LessonOrderRequest request) {
        return ApiResponse.success(courseService.reorderModuleLessons(user, courseId, moduleId, request));
    }
}
