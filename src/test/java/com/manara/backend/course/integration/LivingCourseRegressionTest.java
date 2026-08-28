package com.manara.backend.course.integration;

import com.manara.backend.common.json.Patch;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.QuizAnswerRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.dto.QuizSubmissionRequest;
import com.manara.backend.quiz.service.QuizAttemptService;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.moduleWithExam;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.quiz;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A live course with real learners in it, edited the way an instructor actually edits one.
 *
 * <p>Every other test in this package isolates one operation. This one does not: it builds a
 * published, paid, modular course with a YouTube lesson, a Vimeo lesson, a lesson quiz, a module
 * exam and a final exam, puts five learners into it in five different states, and then walks an
 * instructor through fourteen consecutive edits — metadata, pricing, lessons, videos, modules,
 * ordering, all three kinds of assessment, and a deletion.
 *
 * <p>After every single one of them it re-checks the same invariant set: the course is still
 * published, every learner is still enrolled, every completed lesson is still completed, the
 * submitted attempt still exists, and the learner who paid twenty still shows a purchase of twenty.
 * A defect that only appears on the ninth edit, or only once a course has both a module exam and a
 * final one, is exactly the kind these single-operation tests cannot see.
 *
 * <p>Rich-content lessons are deliberately absent: they are being built in parallel on
 * {@code feature/rich-content-lessons} and do not exist on this branch. See the fix report's
 * parallel-development section.
 */
class LivingCourseRegressionTest extends AbstractCourseAuthoringTest {

    @Autowired QuizAttemptService quizAttemptService;

    private static final String YOUTUBE = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String VIMEO = "https://vimeo.com/76979871";

    private Long courseId;

    /** Enrolled, and has done nothing at all. */
    private User studentA;
    /** Bought the course outright, at the price it had on the day. */
    private User studentB;
    /** Part-way through. */
    private User studentC;
    /** Has a submitted quiz attempt. */
    private User studentD;
    /** Has finished every lesson. */
    private User studentE;

    private Long attemptId;
    private List<Long> completedByC;
    private List<Long> completedByE;

    @BeforeEach
    void buildALivingCourse() {
        CourseRequest create = modularCourse("Living course", CourseStatus.PUBLISHED,
                moduleWithExam("Module one", quiz("Module one exam"),
                        videoLesson("Intro on YouTube", YOUTUBE),
                        lessonWithVideoAndQuiz("Practice on Vimeo", VIMEO, quiz("Lesson quiz"))),
                module("Module two",
                        videoLesson("Deep dive", YOUTUBE),
                        videoLesson("Wrap up", VIMEO)));
        create.setAccessType(CourseAccessType.PURCHASE);
        create.setPurchasePrice(new BigDecimal("20.00"));
        create.setFinalQuiz(quiz("Final exam"));

        courseId = courseService.createCourse(instructorUser, create).getId();

        studentA = newStudentUser();
        studentB = newStudentUser();
        studentC = newStudentUser();
        studentD = newStudentUser();
        studentE = newStudentUser();
        List.of(studentA, studentB, studentC, studentD, studentE)
                .forEach(student -> enroll(student, courseId));

        // Recorded the way a completed purchase leaves the books: the price of the day, frozen.
        // Written directly because what this test is about is what happens to that row afterwards,
        // not how checkout produces it — CheckoutProcessorTest owns that.
        recordPurchase(studentB, new BigDecimal("20.00"));

        completedByC = completeFirst(studentC, 2);
        completedByE = completeFirst(studentE, 4);
        attemptId = submitLessonQuiz(studentD);
    }

    // ── The walk ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("fourteen consecutive edits to a published course leave every learner untouched")
    void aPublishedCourseStaysEditableAndItsLearnersStaySafe() {
        edit("rename the course", save -> save.setTitle("Living course, renamed"));
        edit("change the subtitle and the cover", save -> {
            save.setSubtitle(Patch.of("A new subtitle"));
            save.setImage(Patch.of("/uploads/new-cover.png"));
        });
        edit("reprice from 20 to 40", save -> save.setPurchasePrice(new BigDecimal("40.00")));
        edit("rename a lesson", save -> firstLessonOf(save).setTitle("Intro, retitled"));
        edit("swap a YouTube video for a Vimeo one",
                save -> firstLessonOf(save).setVideoUrl(VIMEO));
        edit("add a lesson to module two", save -> {
            var lessons = new ArrayList<>(save.getModules().get(1).getLessons());
            lessons.add(videoLesson("Newly added", YOUTUBE));
            save.getModules().get(1).setLessons(lessons);
        });
        edit("add a third module", save -> {
            var modules = new ArrayList<>(save.getModules());
            modules.add(module("Module three", videoLesson("Bonus", YOUTUBE)));
            save.setModules(modules);
        });
        edit("edit a module's own title", save -> save.getModules().get(0).setTitle("Module one, retitled"));
        edit("move a lesson from module two into module one", save -> {
            var fromLessons = new ArrayList<>(save.getModules().get(1).getLessons());
            LessonRequest moved = fromLessons.removeLast();
            save.getModules().get(1).setLessons(fromLessons);
            var intoLessons = new ArrayList<>(save.getModules().get(0).getLessons());
            intoLessons.add(moved);
            save.getModules().get(0).setLessons(intoLessons);
        });
        edit("add a question to the lesson quiz", save -> {
            QuizRequest lessonQuiz = save.getModules().get(0).getLessons().get(1).getQuiz();
            lessonQuiz.setQuestions(withExtraQuestion(lessonQuiz));
        });
        edit("retitle the module exam", save -> save.getModules().get(0).getQuiz().setTitle("Module one exam, v2"));
        edit("raise the final exam's passing score", save -> save.getFinalQuiz().setPassingScore(80));
        edit("delete a lesson nobody has completed", save -> {
            var lessons = new ArrayList<>(save.getModules().get(1).getLessons());
            lessons.removeIf(l -> "Newly added".equals(l.getTitle()));
            save.getModules().get(1).setLessons(lessons);
        });

        // The dedicated ordering commands, which are not aggregate saves at all.
        InstructorCourseResponse current = load();
        courseService.reorderModules(instructorUser, courseId,
                CourseAuthoringFixtures.order(moduleIdsOf(current).reversed()));
        assertEverybodyIsIntact("reorder the modules");

        current = load();
        Long firstModuleId = current.getModules().getFirst().getId();
        courseService.reorderModuleLessons(instructorUser, courseId, firstModuleId,
                CourseAuthoringFixtures.lessonOrder(moduleLessonIdsOf(current, 0).reversed()));
        assertEverybodyIsIntact("reorder one module's lessons");

        // And the course really did change, rather than every edit having quietly no-opped.
        InstructorCourseResponse finished = load();
        assertThat(finished.getTitle()).isEqualTo("Living course, renamed");
        assertThat(finished.getSubtitle()).isEqualTo("A new subtitle");
        assertThat(finished.getPurchasePrice()).isEqualByComparingTo("40.00");
        assertThat(finished.getModules()).hasSize(3);
        assertThat(finished.getFinalQuiz().getPassingScore()).isEqualTo(80);
    }

    @Test
    @DisplayName("a rejected edit changes nothing at all — the course keeps every lesson and learner")
    void aRejectedEditRollsBackCompletely() {
        InstructorCourseResponse before = load();
        List<Long> lessonIdsBefore = allLessonIds();
        assertThat(lessonIdsBefore).hasSize(4);

        // Invalid: a lesson is given a video Manara cannot play. The save also carries a legitimate
        // rename and a legitimate reprice, so if any of it lands, the transaction leaked.
        CourseRequest doomed = echoOf(before);
        doomed.setTitle("Should never be stored");
        doomed.setPurchasePrice(new BigDecimal("99.00"));
        firstLessonOf(doomed).setVideoUrl("https://example.com/nothing-playable.mp4");

        try {
            courseService.updateCourse(instructorUser, courseId, doomed);
            throw new AssertionError("the invalid save should have been refused");
        } catch (com.manara.backend.common.exception.BusinessException expected) {
            assertThat(expected.getMessageCode()).isEqualTo("error.course.lessonVideoProviderUnsupported");
        }

        InstructorCourseResponse after = load();
        assertThat(after.getTitle()).isEqualTo(before.getTitle());
        assertThat(after.getPurchasePrice()).isEqualByComparingTo("20.00");
        assertThat(allLessonIds()).isEqualTo(lessonIdsBefore);
        assertThat(after.getModules()).hasSize(2);
        assertEverybodyIsIntact("a fully rejected save");
    }

    // ── One edit, then the full invariant sweep ─────────────────────────────

    /**
     * Loads the course as the editor would, applies one change to the echoed payload, saves it, and
     * re-checks every learner.
     *
     * <p>Reloading before each edit is the point: this is a sequence, and each step has to be built
     * on the revision the previous one produced, exactly as a real editor does.
     */
    private void edit(String what, java.util.function.Consumer<CourseRequest> change) {
        CourseRequest save = echoOf(load());
        change.accept(save);
        courseService.updateCourse(instructorUser, courseId, save);
        assertEverybodyIsIntact(what);
    }

    /** Everything that must still be true about the learners, whatever the instructor just did. */
    private void assertEverybodyIsIntact(String afterWhat) {
        assertThat(reload(courseId).getStatus())
                .as("course still published after: %s", afterWhat)
                .isEqualTo(CourseStatus.PUBLISHED);

        for (User student : List.of(studentA, studentB, studentC, studentD, studentE)) {
            assertThat(enrollmentRepository.findByCourseIdAndStudentId(
                    courseId, studentProfileOf(student).getId()))
                    .as("enrollment survives: %s", afterWhat)
                    .isPresent();
        }

        assertThat(completedIdsOf(studentC))
                .as("partial progress survives: %s", afterWhat)
                .containsExactlyElementsOf(completedByC);
        assertThat(completedIdsOf(studentE))
                .as("finished learner's progress survives: %s", afterWhat)
                .containsExactlyElementsOf(completedByE);
        assertThat(completedIdsOf(studentA))
                .as("a learner with no progress gains none: %s", afterWhat)
                .isEmpty();

        assertThat(jdbcTemplate.queryForList(
                "SELECT id FROM quiz_attempts WHERE id = ?", attemptId))
                .as("submitted attempt survives: %s", afterWhat)
                .hasSize(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT question_text FROM quiz_attempt_answers WHERE attempt_id = ?", attemptId))
                .as("the attempt's answers survive: %s", afterWhat)
                .hasSize(2);

        assertThat(purchasePriceOf(studentB))
                .as("the historical purchase price is never rewritten: %s", afterWhat)
                .isEqualByComparingTo("20.00");
    }

    // ── World-building helpers ──────────────────────────────────────────────

    private InstructorCourseResponse load() {
        return courseService.getCourseForEditing(instructorUser, courseId);
    }

    private static LessonRequest videoLesson(String title, String videoUrl) {
        LessonRequest request = lesson(title);
        request.setVideoUrl(videoUrl);
        return request;
    }

    private static LessonRequest lessonWithVideoAndQuiz(String title, String videoUrl, QuizRequest quiz) {
        LessonRequest request = videoLesson(title, videoUrl);
        request.setQuiz(quiz);
        return request;
    }

    /** The first lesson of the first module, whatever the payload's shape currently is. */
    private static LessonRequest firstLessonOf(CourseRequest request) {
        ModuleRequest first = request.getModules().getFirst();
        return first.getLessons().getFirst();
    }

    private static List<QuizQuestionRequest> withExtraQuestion(QuizRequest quiz) {
        var questions = new ArrayList<>(quiz.getQuestions());
        questions.add(QuizQuestionRequest.builder()
                .id("added-question")
                .text("A question added after learners had already sat this quiz")
                .correctOptionId("added-right")
                .options(List.of(
                        QuizOptionRequest.builder().id("added-right").text("Right").build(),
                        QuizOptionRequest.builder().id("added-wrong").text("Wrong").build()))
                .build());
        return questions;
    }

    private List<Long> allLessonIds() {
        return lessonRepository.findCourseLessonsInReadingOrder(courseId).stream()
                .map(l -> l.getId())
                .sorted()
                .toList();
    }

    private List<Long> completedIdsOf(User studentUser) {
        return completedLessonRepository
                .findCompletedLessonIdsByStudentIdAndCourseId(studentProfileOf(studentUser).getId(), courseId)
                .stream().sorted().toList();
    }

    /** Marks the first {@code count} lessons of the course complete, in reading order. */
    private List<Long> completeFirst(User studentUser, int count) {
        Student student = studentProfileOf(studentUser);
        List<Long> completed = new ArrayList<>();
        lessonRepository.findCourseLessonsInReadingOrder(courseId).stream()
                .limit(count)
                .forEach(lesson -> {
                    completedLessonRepository.save(
                            CompletedLesson.builder().student(student).lesson(lesson).build());
                    completed.add(lesson.getId());
                });
        return completed.stream().sorted().toList();
    }

    /** A settled one-off purchase at the price of the day. */
    private void recordPurchase(User studentUser, BigDecimal paid) {
        jdbcTemplate.update(
                "INSERT INTO course_purchases (course_id, student_id, list_price, amount_paid, "
                        + "currency, payment_reference, purchased_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'EGP', 'test-ref', now(), now())",
                courseId, studentProfileOf(studentUser).getId(), paid, paid);
    }

    private BigDecimal purchasePriceOf(User studentUser) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT amount_paid FROM course_purchases WHERE course_id = ? AND student_id = ?",
                courseId, studentProfileOf(studentUser).getId());
        return (BigDecimal) row.get("amount_paid");
    }

    /**
     * Student D sits the lesson quiz and answers both questions.
     *
     * <p>The attempt is what the later edits are checked against: an instructor adding a question
     * to this quiz, or retitling the exam beside it, must not reach back into what D submitted.
     */
    private Long submitLessonQuiz(User studentUser) {
        completeFirst(studentUser, 1);
        InstructorQuizResponse lessonQuiz = load().getModules().getFirst().getLessons().get(1).getQuiz();

        List<QuizAnswerRequest> answers = lessonQuiz.getQuestions().stream()
                .map(question -> QuizAnswerRequest.builder()
                        .questionId(question.getId())
                        .optionId(question.getCorrectOptionId())
                        .build())
                .toList();

        return quizAttemptService.submit(studentUser, courseId, Long.valueOf(lessonQuiz.getId()),
                QuizSubmissionRequest.builder().answers(answers).build()).getAttemptId();
    }
}
