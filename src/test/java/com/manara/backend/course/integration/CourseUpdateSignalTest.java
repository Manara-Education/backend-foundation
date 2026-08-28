package com.manara.backend.course.integration;

import com.manara.backend.common.json.Patch;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.order;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * When learners are told a published course has changed, and — just as importantly — when they are
 * not.
 *
 * <p>The badge is worth nothing if it lights up for a purchase, a background video lookup or a save
 * that changed nothing, so roughly half of these tests are about it staying dark.
 */
class CourseUpdateSignalTest extends AbstractCourseAuthoringTest {

    private InstructorCourseResponse publishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Live course", CourseStatus.PUBLISHED,
                        module("One", lesson("L1")), module("Two", lesson("L2"))));
    }

    private boolean updatedFlagOf(Long courseId) {
        return reload(courseId).hasUpdatesSincePublish();
    }

    @Nested
    @DisplayName("the badge lights up")
    class Lights {

        @Test
        void whenTheTitleChanges() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setTitle("Live course, renamed");

            assertThat(courseService.updateCourse(instructorUser, course.getId(), request)
                    .getHasUpdatesSincePublish()).isTrue();
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenTheDescriptionChanges() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setDescription("A different description entirely, longer than before");

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenTheCoverImageChanges() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setImage(Patch.of("/uploads/new-cover.png"));

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenAModuleIsAdded() {
            var course = publishedCourse();
            var request = echoOf(course);
            var modules = new ArrayList<>(request.getModules());
            modules.add(module("Three", lesson("L3")));
            request.setModules(modules);

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenAModuleIsRenamed() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.getModules().get(0).setTitle("One, renamed");

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenAModuleIsRemoved() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setModules(List.of(request.getModules().get(0)));

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenModulesAreReordered() {
            var course = publishedCourse();
            var ids = moduleIdsOf(course);

            courseService.reorderModules(instructorUser, course.getId(), order(List.of(ids.get(1), ids.get(0))));
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenALessonChanges() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.getModules().get(0).getLessons().get(0).setTitle("L1, retitled");

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenALessonIsPointedAtADifferentVideo() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.getModules().get(0).getLessons().get(0)
                    .setVideoUrl("https://vimeo.com/76979871");

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenAnExamIsAdded() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setFinalQuiz(finalExam());

            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        void whenAnExamQuestionIsEdited() {
            var course = publishedCourse();
            var withExam = echoOf(course);
            withExam.setFinalQuiz(finalExam());
            var published = courseService.updateCourse(instructorUser, course.getId(), withExam);
            courseService.publish(instructorUser, course.getId());
            assertThat(updatedFlagOf(course.getId())).isFalse();

            var edited = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            var quiz = published.getFinalQuiz();
            edited.setFinalQuiz(QuizRequest.builder()
                    .id(quiz.getId())
                    .title(quiz.getTitle())
                    .passingScore(quiz.getPassingScore())
                    .questions(List.of(QuizQuestionRequest.builder()
                            .id(String.valueOf(quiz.getQuestions().get(0).getId()))
                            .text("A completely different question")
                            .correctOptionId(String.valueOf(quiz.getQuestions().get(0).getOptions().get(0).getId()))
                            .orderIndex(0)
                            .options(quiz.getQuestions().get(0).getOptions().stream()
                                    .map(option -> QuizOptionRequest.builder()
                                            .id(String.valueOf(option.getId()))
                                            .text(option.getText())
                                            .build())
                                    .toList())
                            .build()))
                    .build());

            courseService.updateCourse(instructorUser, course.getId(), edited);
            assertThat(updatedFlagOf(course.getId())).isTrue();
        }

        @Test
        @DisplayName("and stays lit when the instructor puts the value back")
        void andStaysLitAfterARevert() {
            var course = publishedCourse();
            var toB = echoOf(course);
            toB.setTitle("B");
            courseService.updateCourse(instructorUser, course.getId(), toB);

            var backToA = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            backToA.setTitle("Live course");
            courseService.updateCourse(instructorUser, course.getId(), backToA);

            assertThat(updatedFlagOf(course.getId()))
                    .as("the course was edited after its baseline; only a re-publish settles that")
                    .isTrue();
        }

        @Test
        @DisplayName("and the timestamp keeps moving across several edits")
        void andTheTimestampAdvances() {
            var course = publishedCourse();
            var first = echoOf(course);
            first.setTitle("First edit");
            courseService.updateCourse(instructorUser, course.getId(), first);
            LocalDateTime afterFirst = reload(course.getId()).getContentUpdatedAt();

            var second = echoOf(courseService.getCourseForEditing(instructorUser, course.getId()));
            second.setTitle("Second edit");
            courseService.updateCourse(instructorUser, course.getId(), second);

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isAfterOrEqualTo(afterFirst);
            assertThat(after.hasUpdatesSincePublish()).isTrue();
        }
    }

    @Nested
    @DisplayName("the badge stays dark")
    class Dark {

        @Test
        @DisplayName("on a course that has just been published for the first time")
        void afterAFirstPublication() {
            var course = publishedCourse();
            assertThat(course.getHasUpdatesSincePublish()).isFalse();
            assertThat(updatedFlagOf(course.getId())).isFalse();
        }

        @Test
        @DisplayName("on a course published from a draft, edits and all")
        void afterPublishingADraftThatWasEdited() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Drafted", CourseStatus.DRAFT, module("One", lesson("L1"))));

            var edited = echoOf(course);
            edited.setTitle("Drafted and edited");
            courseService.updateCourse(instructorUser, course.getId(), edited);

            courseService.publish(instructorUser, course.getId());
            assertThat(updatedFlagOf(course.getId()))
                    .as("publishing is what makes the version; it cannot announce itself as an update to it")
                    .isFalse();
        }

        @Test
        @DisplayName("on a draft, however much it is edited")
        void whileTheCourseIsADraft() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Never live", CourseStatus.DRAFT, module("One", lesson("L1"))));

            var request = echoOf(course);
            request.setTitle("Edited draft");
            var updated = courseService.updateCourse(instructorUser, course.getId(), request);

            assertThat(updated.getHasUpdatesSincePublish()).isFalse();
            assertThat(reload(course.getId()).getContentUpdatedAt())
                    .as("the timestamp still moves — it is the baseline that is missing")
                    .isNotNull();
            assertThat(reload(course.getId()).getLastPublishedAt()).isNull();
        }

        @Test
        @DisplayName("on a course that was withdrawn from the catalogue")
        void whileTheCourseIsUnpublished() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setTitle("Edited then withdrawn");
            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();

            courseService.unpublish(instructorUser, course.getId());
            assertThat(updatedFlagOf(course.getId()))
                    .as("nobody is looking at an unpublished course, so there is nothing to announce")
                    .isFalse();
        }

        @Test
        @DisplayName("when the instructor re-publishes, settling the new version")
        void afterARepublish() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setTitle("Edited");
            courseService.updateCourse(instructorUser, course.getId(), request);
            assertThat(updatedFlagOf(course.getId())).isTrue();

            courseService.publish(instructorUser, course.getId());
            assertThat(updatedFlagOf(course.getId())).isFalse();
        }

        @Test
        @DisplayName("when the save changed nothing at all")
        void afterANoOpSave() {
            var course = publishedCourse();
            LocalDateTime before = reload(course.getId()).getContentUpdatedAt();

            courseService.updateCourse(instructorUser, course.getId(), echoOf(course));

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before);
            assertThat(after.hasUpdatesSincePublish()).isFalse();
        }

        @Test
        @DisplayName("when the same subscription price arrives at a different scale")
        void whenAPriceIsResubmittedAtADifferentScale() {
            var request = modularCourse("Subscribed", CourseStatus.PUBLISHED, module("One", lesson("L1")));
            request.setAccessType(com.manara.backend.course.model.CourseAccessType.SUBSCRIPTION);
            request.setSubscriptionPlans(List.of(SubscriptionPlanRequest.builder()
                    .name("Monthly")
                    .unit(com.manara.backend.course.model.SubscriptionUnit.MONTH)
                    .duration(1)
                    .price(new java.math.BigDecimal("100"))
                    .build()));
            var course = courseService.createCourse(instructorUser, request);
            LocalDateTime before = reload(course.getId()).getContentUpdatedAt();

            var echo = echoOf(course);
            echo.setAccessType(com.manara.backend.course.model.CourseAccessType.SUBSCRIPTION);
            echo.setSubscriptionPlans(List.of(SubscriptionPlanRequest.builder()
                    .id(course.getSubscriptionPlans().get(0).getId())
                    .name("Monthly")
                    .unit(com.manara.backend.course.model.SubscriptionUnit.MONTH)
                    .duration(1)
                    .price(new java.math.BigDecimal("100.00"))
                    .build()));
            courseService.updateCourse(instructorUser, course.getId(), echo);

            assertThat(reload(course.getId()).getContentUpdatedAt())
                    .as("100 and 100.00 are the same price")
                    .isEqualTo(before);
        }

        @Test
        @DisplayName("when a learner buys the course")
        void whenAStudentEnrols() {
            var course = publishedCourse();
            LocalDateTime before = reload(course.getId()).getContentUpdatedAt();

            // What a purchase does to the course row: the enrolment count goes up, which moves the
            // generic `updatedAt` stamp. The learner-facing signal must not follow it.
            Course row = reload(course.getId());
            row.setStudentsCount(row.getStudentsCount() + 1);
            courseRepository.saveAndFlush(row);

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before);
            assertThat(after.hasUpdatesSincePublish()).isFalse();
        }

        @Test
        @DisplayName("when a background video lookup fills in a duration")
        void whenABackgroundJobRewritesTheDuration() {
            var course = publishedCourse();
            LocalDateTime before = reload(course.getId()).getContentUpdatedAt();

            Course row = reload(course.getId());
            row.setDuration(3600);
            courseRepository.saveAndFlush(row);

            var after = reload(course.getId());
            assertThat(after.getContentUpdatedAt()).isEqualTo(before);
            assertThat(after.hasUpdatesSincePublish()).isFalse();
            assertThat(after.getUpdatedAt())
                    .as("the generic stamp does move — which is exactly why it cannot drive the badge")
                    .isNotNull();
        }

        @Test
        @DisplayName("when the edit was rejected")
        void whenTheEditFailed() {
            var course = publishedCourse();
            LocalDateTime before = reload(course.getId()).getContentUpdatedAt();

            var invalid = echoOf(course);
            invalid.setTitle("A title that will never be stored");
            invalid.getModules().get(0).getLessons().get(0).setVideoUrl("not a video link at all");

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), invalid))
                    .isInstanceOf(BusinessException.class);

            var after = reload(course.getId());
            assertThat(after.getTitle()).isEqualTo("Live course");
            assertThat(after.getContentUpdatedAt()).isEqualTo(before);
            assertThat(after.hasUpdatesSincePublish()).isFalse();
        }

        @Test
        @DisplayName("when the caller had no business editing the course")
        void whenTheEditWasUnauthorized() {
            var course = publishedCourse();
            LocalDateTime before = reload(course.getId()).getContentUpdatedAt();

            var request = echoOf(course);
            request.setTitle("Hijacked");

            assertThatThrownBy(() -> courseService.updateCourse(newInstructorUser(), course.getId(), request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.notOwner");

            var after = reload(course.getId());
            assertThat(after.getTitle()).isEqualTo("Live course");
            assertThat(after.getContentUpdatedAt()).isEqualTo(before);
        }

        @Test
        @DisplayName("on a legacy row whose timestamps the migration back-filled")
        void onALegacyCourse() {
            var course = publishedCourse();

            // What V5 leaves behind for a course that predates these columns: both timestamps equal.
            Course row = reload(course.getId());
            LocalDateTime backfilled = row.getCreatedAt();
            row.setLastPublishedAt(backfilled);
            row.setContentUpdatedAt(backfilled);
            courseRepository.saveAndFlush(row);

            assertThat(updatedFlagOf(course.getId())).isFalse();
        }

        @Test
        @DisplayName("on a legacy row the migration never reached, whose timestamps are still null")
        void onACourseWithNoTimestampsAtAll() {
            var course = publishedCourse();

            Course row = reload(course.getId());
            row.setLastPublishedAt(null);
            row.setContentUpdatedAt(null);
            courseRepository.saveAndFlush(row);

            assertThat(updatedFlagOf(course.getId())).isFalse();
            assertThat(courseService.getCourseForEditing(instructorUser, course.getId())
                    .getHasUpdatesSincePublish()).isFalse();
        }
    }

    @Nested
    @DisplayName("every surface reports the same thing")
    class Surfaces {

        @Test
        void theEditorTheCatalogueAndTheLearnerViewAgree() {
            var course = publishedCourse();
            var request = echoOf(course);
            request.setTitle("Changed after publication");
            courseService.updateCourse(instructorUser, course.getId(), request);

            assertThat(courseService.getCourseForEditing(instructorUser, course.getId())
                    .getHasUpdatesSincePublish()).isTrue();

            assertThat(courseService.getMyCourses(instructorUser).stream()
                    .filter(c -> c.getId().equals(course.getId()))
                    .findFirst().orElseThrow()
                    .getHasUpdatesSincePublish()).isTrue();

            assertThat(courseService.getCourseDetails(newStudentUser(), course.getId(),
                            com.manara.backend.course.dto.CourseViewMode.DISCOVER)
                    .getCourse().getHasUpdatesSincePublish()).isTrue();
        }

        @Test
        @DisplayName("an enrolled learner sees it on their own course list")
        void theStudentCourseListCarriesIt() {
            var course = publishedCourse();
            var studentUser = newStudentUser();
            enrollmentRepository.save(com.manara.backend.course.model.Enrollment.builder()
                    .course(reload(course.getId()))
                    .student(studentProfileOf(studentUser))
                    .build());

            assertThat(dashboardService.getStudentCourses(studentUser))
                    .filteredOn(c -> c.getId().equals(course.getId()))
                    .allMatch(c -> Boolean.FALSE.equals(c.getHasUpdatesSincePublish()));

            var request = echoOf(course);
            request.setTitle("Changed after they enrolled");
            courseService.updateCourse(instructorUser, course.getId(), request);

            assertThat(dashboardService.getStudentCourses(studentUser))
                    .filteredOn(c -> c.getId().equals(course.getId()))
                    .isNotEmpty()
                    .allMatch(c -> Boolean.TRUE.equals(c.getHasUpdatesSincePublish()));
        }
    }

    private QuizRequest finalExam() {
        return QuizRequest.builder()
                .title("Final exam")
                .passingScore(70)
                .questions(List.of(QuizQuestionRequest.builder()
                        .text("What is the answer?")
                        .correctOptionId("option-2")
                        .orderIndex(0)
                        .options(List.of(
                                QuizOptionRequest.builder().id("option-1").text("Wrong").orderIndex(0).build(),
                                QuizOptionRequest.builder().id("option-2").text("Right").orderIndex(1).build()))
                        .build()))
                .build();
    }
}
