package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.ContentChangeState;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.lesson.service.LessonService;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.contentLesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.document;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.documentWithLinkAndCta;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.modularCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.module;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole lifecycle, against a real database: author, publish, enrol, complete, edit, re-publish.
 *
 * <p>The unit tests prove each mechanism. This proves they compose — which is the only place the
 * interesting failures live, because every one of them is a claim about two things at once. That
 * completion survives an edit is a claim about the completion table <em>and</em> the authoring path.
 * That a late-joining learner sees no badge is a claim about the change log <em>and</em> the
 * enrollment's timestamp. Neither is checkable in isolation.
 *
 * <p>Enrollments are placed in time by SQL rather than slept into position, following
 * {@link StudentCourseUpdateTest} — {@code enrolled_at} is {@code updatable = false}, and the two
 * cases this feature turns on are "enrolled before the edit" and "enrolled after it".
 */
class RichContentLessonLifecycleTest extends AbstractCourseAuthoringTest {

    private static final LocalDateTime LONG_BEFORE = LocalDateTime.now().minusDays(30);
    private static final LocalDateTime LONG_AFTER = LocalDateTime.now().plusDays(30);

    @Autowired LessonService lessonService;

    /** A live course with one video lesson and one content lesson, which is the mixed case. */
    private InstructorCourseResponse mixedPublishedCourse() {
        return courseService.createCourse(instructorUser,
                modularCourse("Live course", CourseStatus.PUBLISHED,
                        module("One",
                                lesson("Watch this"),
                                contentLesson("Read this", "النسخة الأولى"))));
    }

    private User earlyLearner(Long courseId) {
        courseExistedSince(courseId, LONG_BEFORE.minusDays(1));
        User student = newStudentUser();
        enrolledAt(enroll(student, courseId).getId(), LONG_BEFORE);
        return student;
    }

    private User lateLearner(Long courseId) {
        User student = newStudentUser();
        enrolledAt(enroll(student, courseId).getId(), LONG_AFTER);
        return student;
    }

    private List<LessonResponse> lessonsOf(CourseDetailsResponse details) {
        return details.getModules().stream().flatMap(m -> m.getLessons().stream()).toList();
    }

    private LessonResponse lessonTitled(User student, Long courseId, String title) {
        return lessonsOf(detailsFor(student, courseId)).stream()
                .filter(l -> title.equals(l.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no lesson titled " + title));
    }

    private Long lessonIdTitled(InstructorCourseResponse course, String title) {
        return course.getModules().stream()
                .flatMap(m -> m.getLessons().stream())
                .filter(l -> title.equals(l.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no lesson titled " + title))
                .getId();
    }

    /** Rewrites the content lesson's body through the course editor, as an instructor would. */
    private void editTheContentLesson(InstructorCourseResponse course, String body) {
        var request = echoOf(course);
        request.getModules().get(0).getLessons().stream()
                .filter(l -> "Read this".equals(l.getTitle()))
                .forEach(l -> l.setRichContent(document(body)));
        courseService.updateCourse(instructorUser, course.getId(), request);
    }

    // =========================================================================

    @Nested
    @DisplayName("authoring")
    class Authoring {

        @Test
        void aCourseCanMixVideoAndContentLessons() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            var lessons = lessonsOf(detailsFor(student, course.getId()));

            assertThat(lessons).extracting(LessonResponse::getContentType)
                    .containsExactly(LessonContentType.VIDEO, LessonContentType.RICH_CONTENT);
            // Each carries its own content and nothing of the other's, so a client renders one of
            // two things from a stated fact rather than guessing from a null.
            assertThat(lessons.get(0).getVideoUrl()).isNotNull();
            assertThat(lessons.get(0).getRichContent()).isNull();
            assertThat(lessons.get(1).getVideoUrl()).isNull();
            assertThat(lessons.get(1).getRichContent()).contains("النسخة الأولى");
        }

        @Test
        void aPublishedCourseMayConsistEntirelyOfContentLessons() {
            // Publication requires lessons, not videos. A course of written lessons is a course.
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Written course", CourseStatus.PUBLISHED,
                            module("One", contentLesson("Read", "محتوى"))));

            assertThat(reload(course.getId()).getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        }

        @Test
        void anUnsafeDocumentIsRefusedAndTheCourseIsLeftAlone() {
            var course = mixedPublishedCourse();
            String originalBody = lessonTitled(earlyLearner(course.getId()), course.getId(), "Read this")
                    .getRichContent();

            var request = echoOf(course);
            request.getModules().get(0).getLessons().get(1).setRichContent(
                    documentWithLinkAndCta("اضغط", "javascript:alert(1)", "https://example.com"));

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), request))
                    .isInstanceOf(BusinessException.class);

            // Synchronization is destructive, so a payload refused half way would have rewritten the
            // lessons before it. Nothing moved.
            User student = earlyLearner(course.getId());
            assertThat(lessonTitled(student, course.getId(), "Read this").getRichContent())
                    .isEqualTo(originalBody);
            assertThat(lessonTitled(student, course.getId(), "Watch this").getVideoUrl()).isNotNull();
        }

        @Test
        void whatIsStoredIsTheSanitizedDocument() {
            var course = courseService.createCourse(instructorUser,
                    modularCourse("Sanitized", CourseStatus.PUBLISHED,
                            module("One", LessonRequest.builder()
                                    .title("Read")
                                    .contentType(LessonContentType.RICH_CONTENT)
                                    .richContent("""
                                            {"blocks":[
                                              {"type":"paragraph","onclick":"steal()",
                                               "content":[{"type":"text","text":"نص"}]},
                                              {"type":"iframe","src":"https://evil.example"}]}""")
                                    .build())));

            String stored = jdbcTemplate.queryForObject(
                    "SELECT rich_content FROM lessons WHERE course_id = ?", String.class, course.getId());

            assertThat(stored).contains("نص")
                    .doesNotContain("onclick").doesNotContain("iframe").doesNotContain("evil.example");
        }
    }

    @Nested
    @DisplayName("a learner who enrolled before the edit")
    class EnrolledBeforeTheEdit {

        @Test
        void seesTheLatestContentAndAnUpdatedBadge() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            editTheContentLesson(course, "النسخة الثانية");

            var lesson = lessonTitled(student, course.getId(), "Read this");
            assertThat(lesson.getRichContent()).contains("النسخة الثانية");
            assertThat(lesson.getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
        }

        @Test
        void seesTheBadgeOnTheLessonPageAsWellAsTheCurriculum() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());
            Long lessonId = lessonIdTitled(course, "Read this");

            editTheContentLesson(course, "النسخة الثانية");

            // The lesson page is the one screen the updated material is actually on. A badge in the
            // curriculum that vanishes when the learner opens the lesson explains nothing.
            var details = lessonService.getLesson(student, course.getId(), lessonId);
            assertThat(details.getLesson().getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
        }

        @Test
        void keepsCompletionAndProgressWhenTheCompletedLessonIsEdited() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());
            Long lessonId = lessonIdTitled(course, "Read this");

            var completion = lessonService.markLessonCompleted(student, course.getId(), lessonId);
            assertThat(completion.getCompleted()).isTrue();
            int progressBefore = completion.getCourseProgress();

            editTheContentLesson(course, "النسخة الثانية");

            var lesson = lessonTitled(student, course.getId(), "Read this");
            // Completed and Updated coexist. They answer different questions, and an edit to the
            // material is not a reason to take a learner's progress away from them.
            assertThat(lesson.getIsCompleted()).isTrue();
            assertThat(lesson.getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(detailsFor(student, course.getId()).getProgress()).isEqualTo(progressBefore);
        }

        @Test
        void keepsItsEnrollmentThroughTheUpdate() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());
            var before = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(student).getId())
                    .orElseThrow();

            editTheContentLesson(course, "النسخة الثانية");

            var after = enrollmentRepository
                    .findByCourseIdAndStudentId(course.getId(), studentProfileOf(student).getId())
                    .orElseThrow();
            assertThat(after.getId()).isEqualTo(before.getId());
            assertThat(after.getEnrolledAt()).isEqualTo(before.getEnrolledAt());
            // Scoped to this course: the container is shared across the class, so a global count
            // would be measuring the other tests rather than this one. What matters is that
            // publishing an update did not duplicate this learner's row.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM enrollments WHERE course_id = ?", Integer.class, course.getId()))
                    .isEqualTo(1);
        }

        @Test
        void isToldWhenALinkOrAButtonChanges() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.getModules().get(0).getLessons().get(1).setRichContent(
                    documentWithLinkAndCta("اقرأ المزيد", "https://example.com/new",
                            "https://example.com/exercise"));
            courseService.updateCourse(instructorUser, course.getId(), request);

            var lesson = lessonTitled(student, course.getId(), "Read this");
            assertThat(lesson.getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
            assertThat(lesson.getRichContent())
                    .contains("https://example.com/new")
                    .contains("https://example.com/exercise");
        }

        @Test
        void isToldWhenTheLessonChangesKind() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            var target = request.getModules().get(0).getLessons().get(1);
            target.setContentType(LessonContentType.VIDEO);
            target.setVideoUrl("https://vimeo.com/76979871");
            courseService.updateCourse(instructorUser, course.getId(), request);

            var lesson = lessonTitled(student, course.getId(), "Read this");
            assertThat(lesson.getContentType()).isEqualTo(LessonContentType.VIDEO);
            assertThat(lesson.getChange().getState()).isEqualTo(ContentChangeState.UPDATED);
        }

        @Test
        void isNotToldAnythingWhenTheCourseIsMerelyRepriced() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            var request = echoOf(course);
            request.setAccessType(com.manara.backend.course.model.CourseAccessType.PURCHASE);
            request.setPurchasePrice(java.math.BigDecimal.valueOf(499));
            courseService.updateCourse(instructorUser, course.getId(), request);

            // Pricing is commerce, not curriculum. Nothing about a price can reach a lesson's badge —
            // structurally, since none of it implements TrackedContent.
            assertThat(lessonTitled(student, course.getId(), "Read this").getChange().getState())
                    .isEqualTo(ContentChangeState.UNCHANGED);
            assertThat(lessonTitled(student, course.getId(), "Watch this").getChange().getState())
                    .isEqualTo(ContentChangeState.UNCHANGED);
        }

        @Test
        void isNotToldAnythingWhenTheLessonIsSavedUnchanged() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            // The instructor opens the course editor and saves without touching anything. The stored
            // document is canonical, so the round trip has to compare equal.
            courseService.updateCourse(instructorUser, course.getId(), echoOf(course));

            assertThat(lessonTitled(student, course.getId(), "Read this").getChange().getState())
                    .isEqualTo(ContentChangeState.UNCHANGED);
        }
    }

    @Nested
    @DisplayName("a learner who enrolled after the edit")
    class EnrolledAfterTheEdit {

        @Test
        void seesTheLatestContentWithNoBadge() {
            var course = mixedPublishedCourse();
            editTheContentLesson(course, "النسخة الثانية");

            User student = lateLearner(course.getId());

            var lesson = lessonTitled(student, course.getId(), "Read this");
            assertThat(lesson.getRichContent()).contains("النسخة الثانية");
            // The lesson already held its latest content when they joined. Telling them it changed
            // would be describing an edit to something they never had.
            assertThat(lesson.getChange().getState()).isEqualTo(ContentChangeState.UNCHANGED);
        }
    }

    @Nested
    @DisplayName("completion")
    class Completion {

        @Test
        void isIdempotentAcrossRepeatedRequests() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());
            Long lessonId = lessonIdTitled(course, "Read this");

            var first = lessonService.markLessonCompleted(student, course.getId(), lessonId);
            var second = lessonService.markLessonCompleted(student, course.getId(), lessonId);
            var third = lessonService.markLessonCompleted(student, course.getId(), lessonId);

            assertThat(first.getCompleted()).isTrue();
            assertThat(second.getCourseProgress()).isEqualTo(first.getCourseProgress());
            assertThat(third.getCourseProgress()).isEqualTo(first.getCourseProgress());
            // One row, one progress figure — no duplicate, no double count.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM completed_lessons WHERE lesson_id = ?", Integer.class, lessonId))
                    .isEqualTo(1);
        }

        @Test
        void keepsTheFirstCompletionInstantWhenClaimedAgain() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());
            Long lessonId = lessonIdTitled(course, "Read this");

            lessonService.markLessonCompleted(student, course.getId(), lessonId);
            LocalDateTime first = completedAtOf(lessonId);
            lessonService.markLessonCompleted(student, course.getId(), lessonId);

            // A learner completed the lesson once, at the moment they first said so, however many
            // times the click was delivered.
            assertThat(completedAtOf(lessonId)).isEqualTo(first);
        }

        @Test
        void countsContentAndVideoLessonsTheSameWayTowardsProgress() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            var afterContent = lessonService.markLessonCompleted(
                    student, course.getId(), lessonIdTitled(course, "Read this"));
            assertThat(afterContent.getCourseProgress()).isEqualTo(50);

            var afterVideo = lessonService.markLessonCompleted(
                    student, course.getId(), lessonIdTitled(course, "Watch this"));

            // Progress is generic lesson completion, not video playback. Two lessons of different
            // kinds finish a two-lesson course.
            assertThat(afterVideo.getCourseProgress()).isEqualTo(100);
            assertThat(afterVideo.getCourseCompleted()).isTrue();
        }

        @Test
        void survivesAnEditToTheLessonThatWasCompleted() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());

            lessonService.markLessonCompleted(student, course.getId(), lessonIdTitled(course, "Read this"));
            lessonService.markLessonCompleted(student, course.getId(), lessonIdTitled(course, "Watch this"));
            assertThat(detailsFor(student, course.getId()).getProgress()).isEqualTo(100);

            editTheContentLesson(course, "النسخة الثانية");

            // Course progress must not fall because existing content was edited.
            assertThat(detailsFor(student, course.getId()).getProgress()).isEqualTo(100);
            assertThat(lessonsOf(detailsFor(student, course.getId())))
                    .allMatch(LessonResponse::getIsCompleted);
        }
    }

    /**
     * The two content features meeting.
     *
     * <p>Legacy leniency and lesson content types were built independently and overlap in exactly
     * one place: both decide, per lesson, which rules a save applies. The composition is that the
     * content type is the outer question and the carried-video exemption applies inside the video
     * branch only — so these check the corners where getting that ordering wrong would show.
     */
    @Nested
    @DisplayName("content lessons alongside a legacy video")
    class AlongsideLegacyVideo {

        /** A stored URL no adapter claims — the shape a migrated row is really in. */
        private static final String LEGACY_VIDEO_URL = "https://media.nafs.edu.sa/vod/legacy-4821.mp4";

        private void makeVideoLegacy(Long lessonId) {
            jdbcTemplate.update(
                    "UPDATE lessons SET video_url = ?, video_provider = NULL, external_video_id = NULL, "
                            + "video_thumbnail_url = NULL WHERE id = ?",
                    LEGACY_VIDEO_URL, lessonId);
        }

        @Test
        @DisplayName("a content lesson can be added beside a video Manara can no longer play")
        void aContentLessonCanBeAddedBesideALegacyVideo() {
            var course = mixedPublishedCourse();
            makeVideoLegacy(lessonIdTitled(course, "Watch this"));
            var reloaded = courseService.getCourseForEditing(instructorUser, course.getId());

            var request = echoOf(reloaded);
            var withExtra = new java.util.ArrayList<>(request.getModules().get(0).getLessons());
            withExtra.add(contentLesson("Read this too", "درس جديد"));
            request.getModules().get(0).setLessons(withExtra);

            // The legacy row is carried, so it is not re-resolved; the new content lesson is
            // sanitized in full. Neither exempts the other.
            courseService.updateCourse(instructorUser, course.getId(), request);

            User student = earlyLearner(course.getId());
            assertThat(lessonTitled(student, course.getId(), "Read this too").getRichContent())
                    .contains("درس جديد");
            assertThat(lessonTitled(student, course.getId(), "Watch this").getVideoUrl())
                    .isEqualTo(LEGACY_VIDEO_URL);
        }

        @Test
        @DisplayName("a content lesson's document is still sanitized when a legacy video is carried")
        void leniencyDoesNotLeakIntoTheDocumentRules() {
            var course = mixedPublishedCourse();
            makeVideoLegacy(lessonIdTitled(course, "Watch this"));
            var reloaded = courseService.getCourseForEditing(instructorUser, course.getId());

            var request = echoOf(reloaded);
            request.getModules().get(0).getLessons().get(1).setRichContent(
                    documentWithLinkAndCta("اضغط", "javascript:alert(1)", "https://example.com"));

            // The exemption is for a video this save is not touching. It is not a licence for the
            // rest of the payload, and a document is never exempt — the sanitizer is the security
            // boundary, and a boundary with an exemption is not one.
            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), request))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a content lesson that still holds a legacy URL is judged on its document")
        void aContentLessonIsNotExemptedByItsRetainedVideo() {
            // A lesson switched from video to content keeps its URL. That retained value must not
            // be what decides whether the lesson is valid — its document is.
            var course = mixedPublishedCourse();
            Long videoLessonId = lessonIdTitled(course, "Watch this");
            makeVideoLegacy(videoLessonId);
            var reloaded = courseService.getCourseForEditing(instructorUser, course.getId());

            var request = echoOf(reloaded);
            var target = request.getModules().get(0).getLessons().get(0);
            target.setContentType(LessonContentType.RICH_CONTENT);
            target.setRichContent(document("صار درسًا مقروءًا"));
            courseService.updateCourse(instructorUser, course.getId(), request);

            User student = earlyLearner(course.getId());
            var lesson = lessonTitled(student, course.getId(), "Watch this");
            assertThat(lesson.getContentType()).isEqualTo(LessonContentType.RICH_CONTENT);
            assertThat(lesson.getRichContent()).contains("صار درسًا مقروءًا");
            assertThat(lesson.getVideoUrl()).isNull();
            // Retained in the row for the way back, even though it is a URL Manara cannot play.
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT video_url FROM lessons WHERE id = ?", String.class, videoLessonId))
                    .isEqualTo(LEGACY_VIDEO_URL);
        }
    }

    @Nested
    @DisplayName("a lesson added to a live course")
    class NewLesson {

        @Test
        void reachesExistingLearnersAsNewAndLeavesTheirProgressAlone() {
            var course = mixedPublishedCourse();
            User student = earlyLearner(course.getId());
            lessonService.markLessonCompleted(student, course.getId(), lessonIdTitled(course, "Read this"));
            lessonService.markLessonCompleted(student, course.getId(), lessonIdTitled(course, "Watch this"));

            var request = echoOf(course);
            // echoOf builds its lists with `toList()`, which is immutable — a payload that adds a
            // lesson has to build a new list rather than push onto the echo's.
            var withExtra = new java.util.ArrayList<>(request.getModules().get(0).getLessons());
            withExtra.add(contentLesson("Read this too", "درس جديد"));
            request.getModules().get(0).setLessons(withExtra);
            courseService.updateCourse(instructorUser, course.getId(), request);

            var added = lessonTitled(student, course.getId(), "Read this too");
            assertThat(added.getChange().getState()).isEqualTo(ContentChangeState.NEW);
            assertThat(added.getContentType()).isEqualTo(LessonContentType.RICH_CONTENT);
            // Their two completions stand; the course simply has a third lesson now, so the same
            // work is now two thirds of it rather than all of it.
            assertThat(detailsFor(student, course.getId()).getProgress()).isEqualTo(67);
            assertThat(lessonTitled(student, course.getId(), "Read this").getIsCompleted()).isTrue();
        }
    }

    private LocalDateTime completedAtOf(Long lessonId) {
        return jdbcTemplate.queryForObject(
                "SELECT completed_at FROM completed_lessons WHERE lesson_id = ?",
                java.sql.Timestamp.class, lessonId).toLocalDateTime();
    }
}
