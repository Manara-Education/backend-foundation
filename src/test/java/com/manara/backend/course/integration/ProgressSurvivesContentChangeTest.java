package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.profile.model.Student;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lessonOrder;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A learner's history is keyed to a lesson, never to where that lesson sits.
 *
 * <p>The rule these tests defend is that progress hangs off an immutable id — {@code
 * completed_lessons.lesson_id} — and that nothing in the authoring path can be made to write it.
 * Order is a column on the lesson, so moving a lesson is a fact about the lesson and not about
 * anybody who studied it.
 *
 * <p>Asserted by id rather than by count throughout. A count survives a bug that deletes one row
 * and inserts another; "lesson 123 is still the completed one" does not.
 */
class ProgressSurvivesContentChangeTest extends AbstractCourseAuthoringTest {

    private InstructorCourseResponse publishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Live course", CourseStatus.PUBLISHED,
                        module("One", lesson("L1"), lesson("L2")),
                        module("Two", lesson("L3"), lesson("L4"))));
    }

    /** The ids of the lessons this student has completed, in ascending order. */
    private List<Long> completedIds(Student student, Long courseId) {
        return completedLessonRepository
                .findCompletedLessonIdsByStudentIdAndCourseId(student.getId(), courseId)
                .stream()
                .sorted()
                .toList();
    }

    private Student complete(User studentUser, Long courseId, String... titles) {
        Student student = studentProfileOf(studentUser);
        List<String> wanted = List.of(titles);
        lessonRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .filter(l -> wanted.contains(l.getTitle()))
                .forEach(l -> completedLessonRepository.save(
                        CompletedLesson.builder().student(student).lesson(l).build()));
        return student;
    }

    @Test
    @DisplayName("a completed lesson stays completed when it is reordered inside its module")
    void reorderingWithinAModuleKeepsProgress() {
        var course = publishedCourse();
        User studentUser = newStudentUser();
        enroll(studentUser, course.getId());
        Student student = complete(studentUser, course.getId(), "L1");

        Long completedLessonId = completedIds(student, course.getId()).getFirst();

        List<Long> reversed = new ArrayList<>(moduleLessonIdsOf(course, 0));
        Collections.reverse(reversed);
        courseService.reorderModuleLessons(instructorUser, course.getId(),
                course.getModules().getFirst().getId(), lessonOrder(reversed));

        // The lesson moved from position 0 to position 1; the learner's row did not move at all.
        assertThat(completedIds(student, course.getId())).containsExactly(completedLessonId);
        assertThat(lessonRepository.findById(completedLessonId).orElseThrow().getOrderIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("a completed lesson stays completed when its module is reordered around it")
    void reorderingModulesKeepsProgress() {
        var course = publishedCourse();
        User studentUser = newStudentUser();
        enroll(studentUser, course.getId());
        Student student = complete(studentUser, course.getId(), "L1", "L3");

        List<Long> before = completedIds(student, course.getId());

        List<Long> reversed = new ArrayList<>(moduleIdsOf(course));
        Collections.reverse(reversed);
        courseService.reorderModules(instructorUser, course.getId(), order(reversed));

        assertThat(completedIds(student, course.getId())).isEqualTo(before);
    }

    @Test
    @DisplayName("a completed lesson stays completed when it is moved to another module")
    void reparentingALessonKeepsProgress() {
        var course = publishedCourse();
        User studentUser = newStudentUser();
        enroll(studentUser, course.getId());
        Student student = complete(studentUser, course.getId(), "L2");

        List<Long> before = completedIds(student, course.getId());

        // L2 leaves module One for module Two.
        var request = echoOf(course);
        var moved = request.getModules().getFirst().getLessons().getLast();
        request.getModules().getFirst().setLessons(
                List.of(request.getModules().getFirst().getLessons().getFirst()));
        var second = new ArrayList<>(request.getModules().getLast().getLessons());
        second.add(moved);
        request.getModules().getLast().setLessons(second);
        courseService.updateCourse(instructorUser, course.getId(), request);

        assertThat(completedIds(student, course.getId())).isEqualTo(before);
        var reparented = lessonRepository.findById(before.getFirst()).orElseThrow();
        assertThat(reparented.getModule().getId()).isEqualTo(course.getModules().getLast().getId());
    }

    @Test
    @DisplayName("progress is not duplicated by an edit that re-saves every lesson")
    void aFullResaveDoesNotDuplicateProgress() {
        var course = publishedCourse();
        User studentUser = newStudentUser();
        enroll(studentUser, course.getId());
        Student student = complete(studentUser, course.getId(), "L1", "L2", "L3", "L4");

        var request = echoOf(course);
        request.setTitle("Everything re-submitted");
        courseService.updateCourse(instructorUser, course.getId(), request);

        assertThat(completedLessonRepository
                .countByStudentIdAndLesson_Course_Id(student.getId(), course.getId())).isEqualTo(4);
    }

    @Test
    @DisplayName("the course progress percentage is unchanged by a pure reorder")
    void reorderingDoesNotMoveTheProgressBar() {
        var course = publishedCourse();
        User studentUser = newStudentUser();
        enroll(studentUser, course.getId());
        complete(studentUser, course.getId(), "L1", "L2");

        int before = cardFor(studentUser, course.getId()).getProgress();

        List<Long> reversed = new ArrayList<>(moduleIdsOf(course));
        Collections.reverse(reversed);
        courseService.reorderModules(instructorUser, course.getId(), order(reversed));

        assertThat(cardFor(studentUser, course.getId()).getProgress()).isEqualTo(before).isEqualTo(50);
    }
}
