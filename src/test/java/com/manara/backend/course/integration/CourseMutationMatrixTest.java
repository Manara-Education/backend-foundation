package com.manara.backend.course.integration;

import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.SubscriptionUnit;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.plan;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.quiz;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every editable part of a published course, proved twice over.
 *
 * <h2>Why one matrix rather than a method per field</h2>
 * The bug this whole change set exists to close was never "modules cannot be reordered". It was
 * that a course is an aggregate and the behaviour had only been established for part of it — so
 * modules were fixed while lessons, exams and pricing were left to be discovered later, one
 * complaint at a time. A per-field test file reproduces exactly that: whoever adds the next
 * editable field adds it without a test, because there is no single place that would look empty
 * without one. Here there is. Adding a field to the course means adding a row to this matrix, and
 * a row is four lines.
 *
 * <h2>The two things every row proves</h2>
 * A test that only checks the badge passes for a course whose edit was silently dropped, and a
 * test that only checks persistence passes for a course whose learners are never told. So each
 * mutation asserts both:
 *
 * <ol>
 *   <li><strong>It persisted.</strong> Re-read through {@code getCourseForEditing}, which opens its
 *       own transaction, so the assertion is about rows rather than about objects still sitting in
 *       the session that wrote them.
 *   <li><strong>It was announced.</strong> The course is still {@code PUBLISHED},
 *       {@code contentUpdatedAt} moved past the publication baseline, and
 *       {@code hasUpdatesSincePublish} is true.
 * </ol>
 *
 * <p>And the mirror of it: {@link NoOps} re-submits the same values, in different but equivalent
 * representations where the model allows one, and proves nothing moves. A badge that lights up for
 * an instructor opening a form and closing it again is as broken as one that never lights up.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CourseMutationMatrixTest extends AbstractCourseAuthoringTest {

    /**
     * One row of the matrix: what to change, and how to tell it landed.
     *
     * @param apply  edits the echo of the course as the editor would before saving
     * @param verify re-reads the course and asserts the change is there
     */
    private record Mutation(String name,
                            Consumer<CourseRequest> apply,
                            Consumer<InstructorCourseResponse> verify) {
        @Override
        public String toString() {
            return name;
        }
    }

    private Mutation mutation(String name, Consumer<CourseRequest> apply,
                              Consumer<InstructorCourseResponse> verify) {
        return new Mutation(name, apply, verify);
    }

    /**
     * A course with something of everything: both quiz owners, a nested lesson tree, and
     * subscription pricing with two plans.
     */
    private InstructorCourseResponse fullCourse() {
        var request = CourseRequest.builder()
                .title("Complete")
                .subtitle("Every editable thing")
                .description("A course carrying one of everything the editor can change")
                .image("https://cdn.example.com/cover.png")
                .structure(CourseStructure.MODULES)
                .modules(List.of(
                        module("First", lesson("A1"), lesson("A2")),
                        module("Second", lesson("B1"))))
                .finalQuiz(quiz("Final exam"))
                .accessType(CourseAccessType.SUBSCRIPTION)
                .subscriptionPlans(List.of(
                        plan("Monthly", 1, SubscriptionUnit.MONTH, "50.00"),
                        plan("Yearly", 12, SubscriptionUnit.MONTH, "500.00")))
                .status(CourseStatus.PUBLISHED)
                .build();
        request.getModules().get(0).setQuiz(quiz("Module exam"));
        request.getModules().get(0).getLessons().get(0).setQuiz(quiz("Lesson exam"));
        return courseService.createCourse(instructorUser, request);
    }

    /** The course as an instructor would next load it — a genuine re-read, not the write's echo. */
    private InstructorCourseResponse reloadForEditing(Long courseId) {
        return courseService.getCourseForEditing(instructorUser, courseId);
    }

    // ── Real mutations ──────────────────────────────────────────────────────

    private Stream<Mutation> mutations() {
        return Stream.of(
                // A. Metadata ------------------------------------------------
                mutation("metadata: title",
                        r -> r.setTitle("Renamed"),
                        c -> assertThat(c.getTitle()).isEqualTo("Renamed")),
                mutation("metadata: subtitle",
                        r -> r.setSubtitle("A new subtitle"),
                        c -> assertThat(c.getSubtitle()).isEqualTo("A new subtitle")),
                mutation("metadata: description",
                        r -> r.setDescription("A rewritten description, long enough to be real"),
                        c -> assertThat(c.getDescription())
                                .isEqualTo("A rewritten description, long enough to be real")),
                mutation("metadata: cover image",
                        r -> r.setImage("https://cdn.example.com/new-cover.png"),
                        c -> assertThat(c.getImage()).isEqualTo("https://cdn.example.com/new-cover.png")),

                // B. Structure -----------------------------------------------
                mutation("structure: MODULES to FLAT",
                        r -> {
                            r.setStructure(CourseStructure.FLAT);
                            r.setLessons(List.of(lesson("Now flat")));
                            r.setModules(null);
                        },
                        c -> {
                            assertThat(c.getStructure()).isEqualTo(CourseStructure.FLAT);
                            assertThat(c.getLessons()).extracting(l -> l.getTitle())
                                    .containsExactly("Now flat");
                            assertThat(c.getModules()).isEmpty();
                        }),

                // C. Modules -------------------------------------------------
                mutation("module: add",
                        r -> {
                            var modules = new ArrayList<>(r.getModules());
                            modules.add(module("Third", lesson("C1")));
                            r.setModules(modules);
                        },
                        c -> assertThat(c.getModules()).extracting(m -> m.getTitle())
                                .containsExactly("First", "Second", "Third")),
                mutation("module: rename",
                        r -> r.getModules().get(0).setTitle("First, renamed"),
                        c -> assertThat(c.getModules().get(0).getTitle()).isEqualTo("First, renamed")),
                mutation("module: description",
                        r -> r.getModules().get(0).setDescription("A new module description"),
                        c -> assertThat(c.getModules().get(0).getDescription())
                                .isEqualTo("A new module description")),
                mutation("module: delete",
                        r -> r.setModules(List.of(r.getModules().get(0))),
                        c -> assertThat(c.getModules()).extracting(m -> m.getTitle())
                                .containsExactly("First")),

                // E. Lessons inside modules ----------------------------------
                mutation("module lesson: add",
                        r -> {
                            var lessons = new ArrayList<>(r.getModules().get(0).getLessons());
                            lessons.add(lesson("A3"));
                            r.getModules().get(0).setLessons(lessons);
                        },
                        c -> assertThat(c.getModules().get(0).getLessons()).extracting(l -> l.getTitle())
                                .containsExactly("A1", "A2", "A3")),
                mutation("module lesson: title",
                        r -> r.getModules().get(0).getLessons().get(0).setTitle("A1, renamed"),
                        c -> assertThat(c.getModules().get(0).getLessons().get(0).getTitle())
                                .isEqualTo("A1, renamed")),
                mutation("module lesson: summary",
                        r -> r.getModules().get(0).getLessons().get(0).setSummary("A fresh summary"),
                        c -> assertThat(c.getModules().get(0).getLessons().get(0).getSummary())
                                .isEqualTo("A fresh summary")),
                mutation("module lesson: description",
                        r -> r.getModules().get(0).getLessons().get(0).setDescription("A fresh description"),
                        c -> assertThat(c.getModules().get(0).getLessons().get(0).getDescription())
                                .isEqualTo("A fresh description")),
                mutation("module lesson: video",
                        r -> r.getModules().get(0).getLessons().get(0)
                                .setVideoUrl("https://www.youtube.com/watch?v=aBcDeFgHiJk"),
                        c -> assertThat(c.getModules().get(0).getLessons().get(0).getVideoUrl())
                                .contains("aBcDeFgHiJk")),
                mutation("module lesson: delete",
                        r -> r.getModules().get(0).setLessons(List.of(r.getModules().get(0).getLessons().get(0))),
                        c -> assertThat(c.getModules().get(0).getLessons()).extracting(l -> l.getTitle())
                                .containsExactly("A1")),
                mutation("module lesson: reparent to a sibling module",
                        r -> {
                            var first = r.getModules().get(0);
                            var second = r.getModules().get(1);
                            var moving = first.getLessons().get(1);
                            first.setLessons(List.of(first.getLessons().get(0)));
                            var target = new ArrayList<>(second.getLessons());
                            target.add(moving);
                            second.setLessons(target);
                        },
                        c -> {
                            assertThat(c.getModules().get(0).getLessons()).extracting(l -> l.getTitle())
                                    .containsExactly("A1");
                            assertThat(c.getModules().get(1).getLessons()).extracting(l -> l.getTitle())
                                    .containsExactly("B1", "A2");
                        }),

                // F/G/H. Quizzes, at all three owners ------------------------
                mutation("lesson quiz: title",
                        r -> r.getModules().get(0).getLessons().get(0).getQuiz().setTitle("Lesson exam, renamed"),
                        c -> assertThat(c.getModules().get(0).getLessons().get(0).getQuiz().getTitle())
                                .isEqualTo("Lesson exam, renamed")),
                mutation("lesson quiz: delete",
                        r -> r.getModules().get(0).getLessons().get(0).setQuiz(null),
                        c -> assertThat(c.getModules().get(0).getLessons().get(0).getQuiz()).isNull()),
                mutation("lesson quiz: create where there was none",
                        r -> r.getModules().get(1).getLessons().get(0).setQuiz(quiz("Brand new exam")),
                        c -> assertThat(c.getModules().get(1).getLessons().get(0).getQuiz().getTitle())
                                .isEqualTo("Brand new exam")),
                mutation("module quiz: instructions",
                        r -> r.getModules().get(0).getQuiz().setInstructions("Read every question twice"),
                        c -> assertThat(c.getModules().get(0).getQuiz().getInstructions())
                                .isEqualTo("Read every question twice")),
                mutation("module quiz: passing score",
                        r -> r.getModules().get(0).getQuiz().setPassingScore(85),
                        c -> assertThat(c.getModules().get(0).getQuiz().getPassingScore()).isEqualTo(85)),
                mutation("module quiz: delete",
                        r -> r.getModules().get(0).setQuiz(null),
                        c -> assertThat(c.getModules().get(0).getQuiz()).isNull()),
                mutation("final quiz: title",
                        r -> r.getFinalQuiz().setTitle("Final exam, renamed"),
                        c -> assertThat(c.getFinalQuiz().getTitle()).isEqualTo("Final exam, renamed")),
                mutation("final quiz: add question",
                        r -> {
                            var questions = new ArrayList<>(r.getFinalQuiz().getQuestions());
                            questions.add(QuizQuestionRequest.builder()
                                    .id("new-q")
                                    .text("An added question")
                                    .correctOptionId("new-a")
                                    .options(List.of(
                                            QuizOptionRequest.builder().id("new-a").text("Yes").build(),
                                            QuizOptionRequest.builder().id("new-b").text("No").build()))
                                    .build());
                            r.getFinalQuiz().setQuestions(questions);
                        },
                        c -> assertThat(c.getFinalQuiz().getQuestions()).extracting(q -> q.getText())
                                .contains("An added question")),
                mutation("final quiz: edit question text",
                        r -> r.getFinalQuiz().getQuestions().get(0).setText("A reworded question"),
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getText())
                                .isEqualTo("A reworded question")),
                mutation("final quiz: explanation",
                        r -> r.getFinalQuiz().getQuestions().get(0).setExplanation("Because of this"),
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getExplanation())
                                .isEqualTo("Because of this")),
                mutation("final quiz: AI hint toggle",
                        r -> r.getFinalQuiz().getQuestions().get(0).setHintByAiEnabled(true),
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getHintByAiEnabled()).isTrue()),
                mutation("final quiz: edit an answer option",
                        r -> r.getFinalQuiz().getQuestions().get(0).getOptions().get(1).setText("Reworded option"),
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getOptions())
                                .extracting(o -> o.getText()).contains("Reworded option")),
                mutation("final quiz: add an answer option",
                        r -> {
                            var question = r.getFinalQuiz().getQuestions().get(0);
                            var options = new ArrayList<>(question.getOptions());
                            options.add(QuizOptionRequest.builder().id("extra").text("A third option").build());
                            question.setOptions(options);
                        },
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getOptions()).hasSize(4)),
                mutation("final quiz: delete an answer option",
                        r -> {
                            var question = r.getFinalQuiz().getQuestions().get(0);
                            question.setOptions(List.of(question.getOptions().get(0),
                                    question.getOptions().get(1)));
                        },
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getOptions())
                                .extracting(o -> o.getText()).containsExactly("Right", "Wrong")),
                mutation("final quiz: reorder answer options",
                        r -> {
                            // Neither questions nor options have a focused order command of their
                            // own, so for them the aggregate array is still the authority — unlike
                            // modules and lessons, which do have one and are therefore left alone.
                            var question = r.getFinalQuiz().getQuestions().get(0);
                            var options = new ArrayList<>(question.getOptions());
                            java.util.Collections.reverse(options);
                            question.setOptions(options);
                        },
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getOptions())
                                .extracting(o -> o.getText())
                                .containsExactly("Also wrong", "Wrong", "Right")),
                mutation("final quiz: reorder questions",
                        r -> {
                            var questions = new ArrayList<>(r.getFinalQuiz().getQuestions());
                            java.util.Collections.reverse(questions);
                            r.getFinalQuiz().setQuestions(questions);
                        },
                        c -> assertThat(c.getFinalQuiz().getQuestions()).extracting(q -> q.getText())
                                .containsExactly("Final exam question two", "Final exam question one")),
                mutation("final quiz: delete a question",
                        r -> r.getFinalQuiz().setQuestions(List.of(r.getFinalQuiz().getQuestions().get(0))),
                        c -> assertThat(c.getFinalQuiz().getQuestions()).extracting(q -> q.getText())
                                .containsExactly("Final exam question one")),
                mutation("final quiz: change the correct answer",
                        r -> {
                            var question = r.getFinalQuiz().getQuestions().get(0);
                            question.setCorrectOptionId(question.getOptions().get(1).getId());
                        },
                        c -> assertThat(c.getFinalQuiz().getQuestions().get(0).getCorrectOptionId())
                                .isEqualTo(c.getFinalQuiz().getQuestions().get(0).getOptions().get(1).getId())),
                mutation("final quiz: delete",
                        r -> r.setFinalQuiz(null),
                        c -> assertThat(c.getFinalQuiz()).isNull()),

                // I. Pricing and access --------------------------------------
                mutation("access: SUBSCRIPTION to FREE",
                        r -> {
                            r.setAccessType(CourseAccessType.FREE);
                            r.setSubscriptionPlans(null);
                        },
                        c -> {
                            assertThat(c.getAccessType()).isEqualTo(CourseAccessType.FREE);
                            assertThat(c.getSubscriptionPlans()).isEmpty();
                        }),
                mutation("access: SUBSCRIPTION to PURCHASE with a price",
                        r -> {
                            r.setAccessType(CourseAccessType.PURCHASE);
                            r.setPurchasePrice(new BigDecimal("199.99"));
                            r.setSubscriptionPlans(null);
                        },
                        c -> {
                            assertThat(c.getAccessType()).isEqualTo(CourseAccessType.PURCHASE);
                            assertThat(c.getPurchasePrice()).isEqualByComparingTo("199.99");
                        }),
                mutation("plan: add",
                        r -> {
                            var plans = new ArrayList<>(r.getSubscriptionPlans());
                            plans.add(plan("Quarterly", 3, SubscriptionUnit.MONTH, "140.00"));
                            r.setSubscriptionPlans(plans);
                        },
                        c -> assertThat(c.getSubscriptionPlans()).extracting(p -> p.getName())
                                .containsExactly("Monthly", "Yearly", "Quarterly")),
                mutation("plan: rename",
                        r -> r.getSubscriptionPlans().get(0).setName("Monthly, renamed"),
                        c -> assertThat(c.getSubscriptionPlans().get(0).getName())
                                .isEqualTo("Monthly, renamed")),
                mutation("plan: price",
                        r -> r.getSubscriptionPlans().get(0).setPrice(new BigDecimal("75.00")),
                        c -> assertThat(c.getSubscriptionPlans().get(0).getPrice())
                                .isEqualByComparingTo("75.00")),
                mutation("plan: duration",
                        r -> r.getSubscriptionPlans().get(0).setDuration(2),
                        c -> assertThat(c.getSubscriptionPlans().get(0).getDuration()).isEqualTo(2)),
                mutation("plan: duration unit",
                        r -> r.getSubscriptionPlans().get(0).setUnit(SubscriptionUnit.WEEK),
                        c -> assertThat(c.getSubscriptionPlans().get(0).getUnit())
                                .isEqualTo(SubscriptionUnit.WEEK)),
                mutation("plan: delete",
                        r -> r.setSubscriptionPlans(List.of(r.getSubscriptionPlans().get(0))),
                        c -> assertThat(c.getSubscriptionPlans()).extracting(p -> p.getName())
                                .containsExactly("Monthly")),
                mutation("plan: reorder",
                        r -> {
                            // Plans have no focused order command either, so their order still
                            // comes from the payload array.
                            var plans = new ArrayList<>(r.getSubscriptionPlans());
                            java.util.Collections.reverse(plans);
                            r.setSubscriptionPlans(plans);
                        },
                        c -> assertThat(c.getSubscriptionPlans()).extracting(p -> p.getName())
                                .containsExactly("Yearly", "Monthly")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mutations")
    @DisplayName("persists, stays published, and raises the update signal")
    void aRealEditPersistsAndAnnouncesItself(Mutation mutation) {
        var course = fullCourse();
        // Publishing settles the baseline, so anything that moves afterwards is this edit alone.
        courseService.publish(instructorUser, course.getId());
        var before = reload(course.getId());

        var request = echoOf(course);
        mutation.apply().accept(request);
        courseService.updateCourse(instructorUser, course.getId(), request);

        // 1. It persisted — re-read in a transaction of its own.
        mutation.verify().accept(reloadForEditing(course.getId()));

        // 2. It was announced, and publication was not disturbed on the way.
        var after = reload(course.getId());
        assertThat(after.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(after.hasUpdatesSincePublish()).isTrue();
        assertThat(after.getContentUpdatedAt()).isAfter(before.getContentUpdatedAt());
        assertThat(after.getContentUpdatedAt()).isAfter(after.getLastPublishedAt());
    }

    // ── No-ops ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a semantic no-op")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class NoOps {

        /**
         * Each of these re-submits what is already stored. Several do it in a deliberately
         * different representation — untrimmed whitespace, a different {@code BigDecimal} scale —
         * because those are the cases a naive {@code equals} calls a change.
         */
        private Stream<Mutation> noOps() {
            return Stream.of(
                    mutation("an untouched echo of the whole course", r -> {
                    }, c -> {
                    }),
                    mutation("the same title, with whitespace around it",
                            r -> r.setTitle("  Complete  "), c -> {
                            }),
                    mutation("the same module title, with whitespace around it",
                            r -> r.getModules().get(0).setTitle("  First  "), c -> {
                            }),
                    mutation("the same lesson title, with whitespace around it",
                            r -> r.getModules().get(0).getLessons().get(0).setTitle("  A1  "), c -> {
                            }),
                    mutation("the same access type",
                            r -> r.setAccessType(CourseAccessType.SUBSCRIPTION), c -> {
                            }),
                    mutation("the same plan price at a different scale",
                            r -> r.getSubscriptionPlans().get(0).setPrice(new BigDecimal("50.0000")), c -> {
                            }),
                    mutation("the same plan values, re-sent",
                            r -> {
                                var plan = r.getSubscriptionPlans().get(0);
                                plan.setName("Monthly");
                                plan.setDuration(1);
                                plan.setUnit(SubscriptionUnit.MONTH);
                            }, c -> {
                            }),
                    mutation("the same quiz values, re-sent",
                            r -> {
                                var quiz = r.getFinalQuiz();
                                quiz.setTitle("  Final exam  ");
                                quiz.setPassingScore(60);
                            }, c -> {
                            }),
                    mutation("the same module order, shuffled in the array",
                            r -> {
                                // The aggregate has no say over module order any more, so an array
                                // in a different order is not merely a no-op — it is ignored.
                                var modules = new ArrayList<>(r.getModules());
                                java.util.Collections.reverse(modules);
                                r.setModules(modules);
                            }, c -> {
                            }),
                    mutation("the same lesson order, shuffled in the array",
                            r -> {
                                var lessons = new ArrayList<>(r.getModules().get(0).getLessons());
                                java.util.Collections.reverse(lessons);
                                r.getModules().get(0).setLessons(lessons);
                            }, c -> {
                            }));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("noOps")
        @DisplayName("changes nothing and raises no update signal")
        void aNoOpSaveIsSilent(Mutation mutation) {
            var course = fullCourse();
            courseService.publish(instructorUser, course.getId());
            var before = reload(course.getId());

            var request = echoOf(course);
            mutation.apply().accept(request);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var after = reload(course.getId());
            assertThat(after.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(after.getContentUpdatedAt()).isEqualTo(before.getContentUpdatedAt());
            assertThat(after.hasUpdatesSincePublish()).isFalse();

            // The content is not merely un-announced — it is untouched.
            var reloaded = reloadForEditing(course.getId());
            assertThat(reloaded.getTitle()).isEqualTo("Complete");
            assertThat(reloaded.getModules()).extracting(m -> m.getTitle())
                    .containsExactly("First", "Second");
            assertThat(reloaded.getModules().get(0).getLessons()).extracting(l -> l.getTitle())
                    .containsExactly("A1", "A2");
            assertThat(reloaded.getSubscriptionPlans()).extracting(p -> p.getName())
                    .containsExactly("Monthly", "Yearly");
        }
    }

    // ── The publication baseline, end to end ────────────────────────────────

    @Nested
    @DisplayName("the publication baseline")
    class Baseline {

        @org.junit.jupiter.api.Test
        @DisplayName("settles on publish, lights on a real edit, settles again on republish")
        void theBadgeCyclesWithPublication() {
            var course = fullCourse();

            // 1-2. Freshly published: nothing to announce.
            courseService.publish(instructorUser, course.getId());
            assertThat(reload(course.getId()).hasUpdatesSincePublish()).isFalse();

            // 3-4. A real edit lights it.
            var firstEdit = echoOf(course);
            firstEdit.setTitle("Edited once");
            courseService.updateCourse(instructorUser, course.getId(), firstEdit);
            assertThat(reload(course.getId()).hasUpdatesSincePublish()).isTrue();

            // 5-6. Re-publishing settles the current content as the new baseline.
            courseService.publish(instructorUser, course.getId());
            assertThat(reload(course.getId()).hasUpdatesSincePublish()).isFalse();
            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);

            // 7-8. And another real edit lights it again.
            var reloaded = reloadForEditing(course.getId());
            var secondEdit = echoOf(reloaded);
            secondEdit.setSubtitle("Edited twice");
            courseService.updateCourse(instructorUser, course.getId(), secondEdit);
            assertThat(reload(course.getId()).hasUpdatesSincePublish()).isTrue();
            assertThat(reloadForEditing(course.getId()).getSubtitle()).isEqualTo("Edited twice");
        }

        @org.junit.jupiter.api.Test
        @DisplayName("a course that was never published never claims to have updates")
        void aDraftNeverClaimsUpdates() {
            var draft = courseService.createCourse(instructorUser, CourseRequest.builder()
                    .title("Draft")
                    .description("A draft course, long enough a description to be valid")
                    .structure(CourseStructure.FLAT)
                    .lessons(List.of(lesson("Only lesson")))
                    .accessType(CourseAccessType.FREE)
                    .status(CourseStatus.DRAFT)
                    .build());

            var edit = echoOf(draft);
            edit.setTitle("Draft, edited");
            courseService.updateCourse(instructorUser, draft.getId(), edit);

            var after = reload(draft.getId());
            assertThat(after.getStatus()).isEqualTo(CourseStatus.DRAFT);
            assertThat(after.hasUpdatesSincePublish()).isFalse();
            assertThat(reloadForEditing(draft.getId()).getTitle()).isEqualTo("Draft, edited");
        }
    }
}
