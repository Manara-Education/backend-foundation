package com.manara.backend.course.integration;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.lesson.model.Lesson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Content Manara can no longer produce, but already holds.
 *
 * <p>A published course carries rows written by earlier versions, by migrations and by the NAFS
 * import — among them lesson videos on platforms no adapter recognises. The read path has always
 * been deliberately lenient about those: {@code VideoProviderResolver.describe} keeps such a lesson
 * renderable, and {@code VideoSource} documents the URL as "preserved rather than discarded".
 *
 * <p>The write path was not. Because the editor saves the whole aggregate, every unchanged lesson
 * is echoed back on every save, and each one was re-validated as if it had just been typed — so one
 * legacy row made the entire course uneditable, including its title and its price. These tests fix
 * the boundary where it belongs: strict for a video the instructor actually changed, lenient for
 * one the payload is merely carrying.
 */
class LegacyContentEditingTest extends AbstractCourseAuthoringTest {

    /** A URL from before Manara restricted itself to platforms it can embed. */
    private static final String LEGACY_VIDEO_URL = "https://media.nafs.edu.sa/vod/legacy-4821.mp4";

    /**
     * Puts a course into the state a migrated one is really in: a lesson whose stored URL no
     * adapter claims and whose derived provider column was therefore never filled.
     *
     * <p>By SQL on purpose. The service refuses to write this today, which is correct — the point
     * is not that Manara should accept such a URL now, but that it already holds ones it accepted
     * before, and those must not freeze the course they sit in.
     */
    private void makeLessonVideoLegacy(Long lessonId) {
        jdbcTemplate.update(
                "UPDATE lessons SET video_url = ?, video_provider = NULL, external_video_id = NULL, "
                        + "video_thumbnail_url = NULL WHERE id = ?",
                LEGACY_VIDEO_URL, lessonId);
    }

    private InstructorCourseResponse publishedCourseWithLegacyLesson() {
        CourseRequest create = flatCourse("Legacy course", CourseStatus.PUBLISHED,
                lesson("Lesson one"), lesson("Lesson two"), lesson("Lesson three"));
        create.setAccessType(CourseAccessType.PURCHASE);
        create.setPurchasePrice(new BigDecimal("20.00"));

        InstructorCourseResponse created = courseService.createCourse(instructorUser, create);
        makeLessonVideoLegacy(created.getLessons().get(2).getId());
        return courseService.getCourseForEditing(instructorUser, created.getId());
    }

    @Nested
    @DisplayName("an unchanged legacy video does not block an unrelated edit")
    class UnrelatedEdits {

        @Test
        @DisplayName("the price can be changed while lesson three holds a video Manara cannot play")
        void priceIsEditableAlongsideALegacyVideo() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();
            enroll(newStudentUser(), course.getId());

            CourseRequest save = echoOf(course);
            save.setPurchasePrice(new BigDecimal("40.00"));

            assertThatCode(() -> courseService.updateCourse(instructorUser, course.getId(), save))
                    .doesNotThrowAnyException();

            assertThat(reload(course.getId()).getPurchasePrice()).isEqualByComparingTo("40.00");
        }

        @Test
        @DisplayName("the title can be changed too, and the legacy URL is left exactly as it was")
        void titleIsEditableAndTheLegacyUrlSurvivesUntouched() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();
            Long legacyLessonId = course.getLessons().get(2).getId();

            CourseRequest save = echoOf(course);
            save.setTitle("Renamed while legacy content sits in it");

            courseService.updateCourse(instructorUser, course.getId(), save);

            assertThat(reload(course.getId()).getTitle()).isEqualTo("Renamed while legacy content sits in it");
            Lesson legacy = lessonRepository.findById(legacyLessonId).orElseThrow();
            assertThat(legacy.getVideo().getUrl()).isEqualTo(LEGACY_VIDEO_URL);
            assertThat(legacy.getVideo().getProvider()).isNull();
        }

        @Test
        @DisplayName("the course stays published, enrolled and fully populated across the save")
        void studentStateAndContentSurvive() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();
            var student = newStudentUser();
            enroll(student, course.getId());
            List<Long> lessonIdsBefore = lessonIdsOf(course);

            CourseRequest save = echoOf(course);
            save.setPurchasePrice(new BigDecimal("40.00"));
            courseService.updateCourse(instructorUser, course.getId(), save);

            InstructorCourseResponse after = courseService.getCourseForEditing(instructorUser, course.getId());
            assertThat(after.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
            assertThat(lessonIdsOf(after)).isEqualTo(lessonIdsBefore);
            assertThat(enrollmentRepository.findByCourseIdAndStudentId(
                    course.getId(), studentProfileOf(student).getId())).isPresent();
        }
    }

    @Nested
    @DisplayName("changing a video is still validated strictly")
    class ChangedVideos {

        @Test
        @DisplayName("replacing a good video with an unplayable one is refused, naming the lesson")
        void anEditedVideoMustStillBePlayable() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();

            CourseRequest save = echoOf(course);
            save.getLessons().get(0).setVideoUrl("https://media.example.com/not-a-platform.mp4");

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), save))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.lessonVideoProviderUnsupported");
        }

        @Test
        @DisplayName("editing the legacy lesson itself is held to today's standard")
        void editingTheLegacyLessonMustFixItsVideo() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();

            CourseRequest save = echoOf(course);
            save.getLessons().get(2).setVideoUrl("https://media.nafs.edu.sa/vod/legacy-9999.mp4");

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), save))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.lessonVideoProviderUnsupported");
        }

        @Test
        @DisplayName("a legacy lesson can be repaired by pointing it at a supported platform")
        void aLegacyLessonCanBeMigratedForward() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();
            Long legacyLessonId = course.getLessons().get(2).getId();

            CourseRequest save = echoOf(course);
            save.getLessons().get(2).setVideoUrl("https://vimeo.com/76979871");

            courseService.updateCourse(instructorUser, course.getId(), save);

            Lesson repaired = lessonRepository.findById(legacyLessonId).orElseThrow();
            assertThat(repaired.getVideo().getUrl()).isEqualTo("https://vimeo.com/76979871");
            assertThat(repaired.getVideo().getProvider().name()).isEqualTo("VIMEO");
            assertThat(repaired.getVideo().getExternalId()).isEqualTo("76979871");
        }

        @Test
        @DisplayName("a newly added lesson is validated in full, legacy neighbours or not")
        void newLessonsAreValidatedInFull() {
            InstructorCourseResponse course = publishedCourseWithLegacyLesson();

            CourseRequest save = echoOf(course);
            var lessons = new java.util.ArrayList<>(save.getLessons());
            lessons.add(CourseAuthoringFixtures.lesson("Brand new"));
            lessons.get(lessons.size() - 1).setVideoUrl("https://media.nafs.edu.sa/vod/new-1.mp4");
            save.setLessons(lessons);

            assertThatThrownBy(() -> courseService.updateCourse(instructorUser, course.getId(), save))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.course.lessonVideoProviderUnsupported");
        }
    }
}
