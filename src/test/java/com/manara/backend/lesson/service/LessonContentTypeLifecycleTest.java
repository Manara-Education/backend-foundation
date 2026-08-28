package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseChange;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseChangeRepository;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.CourseContentJournal;
import com.manara.backend.course.service.CourseProgressionService;
import com.manara.backend.course.service.CourseUpdateResolver;
import com.manara.backend.course.service.CourseUpdateWindow;
import com.manara.backend.course.service.LearnerCourseAccess;
import com.manara.backend.lesson.LessonContentFixtures;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.quiz.mapper.QuizMapper;
import com.manara.backend.quiz.service.QuizService;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.video.VideoProviderFixtures;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.service.VideoMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * A lesson's kind, and everything that follows from changing it.
 *
 * <p>The companion to {@link LessonVideoLifecycleTest}, which proves the video story still works.
 * This one proves the other three things this feature claims: that a rich-content lesson can be
 * authored without a video, that switching a lesson's type loses nothing, and that both are
 * described honestly to the learners already enrolled.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LessonContentTypeLifecycleTest {

    private static final Long COURSE_ID = 1L;
    private static final Long LESSON_ID = 10L;
    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @Mock private LessonRepository lessonRepository;
    @Mock private CourseRepository courseRepository;
    @Mock private CourseModuleRepository courseModuleRepository;
    @Mock private CompletedLessonRepository completedLessonRepository;
    @Mock private EnrollmentRepository enrollmentRepository;
    @Mock private LearnerCourseAccess learnerCourseAccess;
    @Mock private CourseProgressionService courseProgressionService;
    @Mock private QuizService quizService;
    @Mock private VideoMetadataService videoMetadataService;
    @Mock private CourseUpdateResolver courseUpdateResolver;
    @Mock private MessageService messageService;
    @Mock private CourseChangeRepository courseChangeRepository;

    private LessonService lessonService;
    private Course course;

    private final User instructorUser = User.builder().id(5L).role(Role.INSTRUCTOR).build();

    @BeforeEach
    void setUp() {
        DurationFormatter durationFormatter = new DurationFormatter(messageService);
        lessonService = new LessonService(
                lessonRepository, courseRepository, courseModuleRepository, completedLessonRepository,
                enrollmentRepository, learnerCourseAccess, new LessonPlacement(lessonRepository),
                courseProgressionService,
                new CourseContentJournal(courseChangeRepository),
                new LessonMapper(durationFormatter, VideoProviderFixtures.resolver()), quizService,
                new QuizMapper(), videoMetadataService, LessonContentFixtures.validator(),
                LessonContentFixtures.writer(), courseUpdateResolver, java.time.Clock.systemUTC());

        lenient().when(courseUpdateResolver.resolve(any(), any()))
                .thenReturn(CourseUpdateWindow.notEnrolled());

        course = Course.builder()
                .id(COURSE_ID)
                .title("Course")
                .structure(CourseStructure.FLAT)
                .duration(0)
                .instructor(Instructor.builder().id(50L).user(instructorUser).build())
                .build();

        given(messageService.get(any(), any())).willReturn("0s");
        given(courseRepository.findByIdForUpdate(COURSE_ID)).willReturn(Optional.of(course));
        given(lessonRepository.saveAndFlush(any(Lesson.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(lessonRepository.save(any(Lesson.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private LessonRequest videoRequest() {
        return LessonRequest.builder().title("Lesson").videoUrl(YOUTUBE_URL).orderIndex(0).build();
    }

    private LessonRequest richRequest(String text) {
        return LessonRequest.builder()
                .title("Lesson")
                .contentType(LessonContentType.RICH_CONTENT)
                .richContent(LessonContentFixtures.document(text))
                .orderIndex(0)
                .build();
    }

    /** A course with learners in it — the only state in which change rows are worth writing. */
    private void givenAPublishedCourse() {
        course.setLastPublishedAt(java.time.LocalDateTime.now().minusDays(7));
    }

    private Lesson storedVideoLesson() {
        Lesson lesson = Lesson.builder()
                .id(LESSON_ID)
                .title("Lesson")
                .contentType(LessonContentType.VIDEO)
                .video(VideoProviderFixtures.resolver().resolve(YOUTUBE_URL).toVideoSource())
                .duration(120)
                .orderIndex(0)
                .course(course)
                .contentUpdatedAt(java.time.LocalDateTime.now().minusDays(3))
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(lesson));
        return lesson;
    }

    private Lesson storedRichLesson(String text) {
        Lesson lesson = Lesson.builder()
                .id(LESSON_ID)
                .title("Lesson")
                .contentType(LessonContentType.RICH_CONTENT)
                .richContent(LessonContentFixtures.sanitizer().sanitize(LessonContentFixtures.document(text)))
                .duration(0)
                .orderIndex(0)
                .course(course)
                .contentUpdatedAt(java.time.LocalDateTime.now().minusDays(3))
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(lesson));
        return lesson;
    }

    // --- creating -------------------------------------------------------------

    @Test
    @DisplayName("creates a rich-content lesson with no video, and asks no provider about it")
    void createsARichContentLesson() {
        LessonResponse response = lessonService.addLesson(instructorUser, COURSE_ID, richRequest("محتوى الدرس"));

        assertThat(response.getContentType()).isEqualTo(LessonContentType.RICH_CONTENT);
        assertThat(response.getRichContent()).contains("محتوى الدرس");
        // Not merely null-safe: the response carries no video fields at all, so a client has nothing
        // to build a player around even by accident.
        assertThat(response.getVideoUrl()).isNull();
        assertThat(response.getVideoProvider()).isNull();
        assertThat(response.getVideoEmbedUrl()).isNull();
        // There is no running time to look up for something that is read.
        verify(videoMetadataService, never()).refreshAsync(any(), any());
    }

    @Test
    @DisplayName("a payload with no content type is a video lesson, as every existing client sends")
    void defaultsToVideoWhenTheTypeIsAbsent() {
        LessonResponse response = lessonService.addLesson(instructorUser, COURSE_ID, videoRequest());

        assertThat(response.getContentType()).isEqualTo(LessonContentType.VIDEO);
        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.YOUTUBE);
    }

    @Test
    @DisplayName("a rich-content lesson is not asked for a video URL")
    void doesNotRequireAVideoForRichContent() {
        assertThatCode(() -> lessonService.addLesson(instructorUser, COURSE_ID, richRequest("نص")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a video lesson is still refused without a playable URL")
    void stillRequiresAVideoForVideoLessons() {
        LessonRequest noUrl = LessonRequest.builder().title("Lesson").orderIndex(0).build();

        assertThatThrownBy(() -> lessonService.addLesson(instructorUser, COURSE_ID, noUrl))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.video.urlRequired");
    }

    @Test
    @DisplayName("an empty document is refused, so formatting with nothing in it cannot be published")
    void refusesAnEmptyDocument() {
        LessonRequest empty = LessonRequest.builder()
                .title("Lesson")
                .contentType(LessonContentType.RICH_CONTENT)
                .richContent("{\"blocks\":[{\"type\":\"paragraph\",\"content\":[]}]}")
                .orderIndex(0)
                .build();

        assertThatThrownBy(() -> lessonService.addLesson(instructorUser, COURSE_ID, empty))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.richContent.empty");
    }

    @Test
    @DisplayName("a document carrying an unsafe URL never reaches the database")
    void refusesAnUnsafeDocumentBeforeCreatingTheRow() {
        LessonRequest hostile = LessonRequest.builder()
                .title("Lesson")
                .contentType(LessonContentType.RICH_CONTENT)
                .richContent("""
                        {"blocks":[{"type":"paragraph","content":[
                          {"type":"text","text":"x","marks":[{"type":"link","href":"javascript:alert(1)"}]}]}]}""")
                .orderIndex(0)
                .build();

        assertThatThrownBy(() -> lessonService.addLesson(instructorUser, COURSE_ID, hostile))
                .isInstanceOf(BusinessException.class);
        verify(lessonRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("what is stored is the sanitized document, not what was submitted")
    void storesTheSanitizedForm() {
        LessonRequest request = LessonRequest.builder()
                .title("Lesson")
                .contentType(LessonContentType.RICH_CONTENT)
                .richContent("""
                        {"blocks":[{"type":"paragraph","onclick":"steal()",
                          "content":[{"type":"text","text":"نص"}]},
                          {"type":"iframe","src":"https://evil.example"}]}""")
                .orderIndex(0)
                .build();

        LessonResponse response = lessonService.addLesson(instructorUser, COURSE_ID, request);

        assertThat(response.getRichContent())
                .contains("نص")
                .doesNotContain("onclick")
                .doesNotContain("iframe")
                .doesNotContain("evil.example");
    }

    // --- switching type -------------------------------------------------------

    @Test
    @DisplayName("switching a video lesson to rich content keeps its video for the way back")
    void switchingToRichContentRetainsTheVideo() {
        Lesson lesson = storedVideoLesson();

        LessonResponse response = lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID, richRequest("المحتوى الجديد"));

        assertThat(lesson.getContentType()).isEqualTo(LessonContentType.RICH_CONTENT);
        assertThat(lesson.getRichContent()).contains("المحتوى الجديد");
        // Retained in the row, so switching back restores the lesson rather than losing it.
        assertThat(lesson.getVideo()).isNotNull();
        assertThat(lesson.getVideo().getUrl()).isEqualTo(YOUTUBE_URL);
        // And withheld from the answer, because it is not what this lesson teaches with any more.
        assertThat(response.getVideoUrl()).isNull();
        // A read contributes no time to the course's length.
        assertThat(lesson.getDuration()).isZero();
    }

    @Test
    @DisplayName("switching a rich-content lesson to video keeps the article for the way back")
    void switchingToVideoRetainsTheDocument() {
        Lesson lesson = storedRichLesson("المقال الأصلي");

        LessonResponse response = lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID, videoRequest());

        assertThat(lesson.getContentType()).isEqualTo(LessonContentType.VIDEO);
        assertThat(lesson.getVideo().getUrl()).isEqualTo(YOUTUBE_URL);
        assertThat(lesson.getRichContent()).contains("المقال الأصلي");
        assertThat(response.getRichContent()).isNull();
        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.YOUTUBE);
    }

    @Test
    @DisplayName("a lesson switched away from video and back is re-measured, not left at zero")
    void reMeasuresAVideoThatComesBack() {
        Lesson lesson = storedVideoLesson();
        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, richRequest("مؤقت"));
        assertThat(lesson.getDuration()).isZero();

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, videoRequest());

        // The URL never changed, so a check on the URL alone would have skipped the lookup and left
        // the lesson claiming a video of zero length.
        verify(videoMetadataService).refreshAsync(LESSON_ID, lesson.getVideo());
    }

    @Test
    @DisplayName("a round trip through the other type returns the lesson it started as")
    void aRoundTripLosesNothing() {
        Lesson lesson = storedVideoLesson();
        String originalUrl = lesson.getVideo().getUrl();

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, richRequest("مقال"));
        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, videoRequest());

        assertThat(lesson.getContentType()).isEqualTo(LessonContentType.VIDEO);
        assertThat(lesson.getVideo().getUrl()).isEqualTo(originalUrl);
        assertThat(lesson.getRichContent()).contains("مقال");
    }

    // --- what learners are told ----------------------------------------------

    @Test
    @DisplayName("editing the document is a content change, so enrolled learners are told")
    void recordsADocumentEditAsAContentChange() {
        givenAPublishedCourse();
        Lesson lesson = storedRichLesson("النسخة الأولى");
        java.time.LocalDateTime before = lesson.getContentUpdatedAt();

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, richRequest("النسخة الثانية"));

        assertThat(recordedChangeTypes()).contains(ContentChangeType.CONTENT_UPDATED);
        // The stamp is what the learner's badge is actually computed from; the log row is what
        // words it. Both have to move, so both are checked.
        assertThat(lesson.getContentUpdatedAt()).isAfter(before);
    }

    @Test
    @DisplayName("changing a lesson's type is a content change — it is the largest edit there is")
    void recordsATypeChangeAsAContentChange() {
        givenAPublishedCourse();
        Lesson lesson = storedVideoLesson();
        java.time.LocalDateTime before = lesson.getContentUpdatedAt();

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, richRequest("مقال"));

        assertThat(recordedChangeTypes()).contains(ContentChangeType.CONTENT_UPDATED);
        assertThat(lesson.getContentUpdatedAt()).isAfter(before);
    }

    @Test
    @DisplayName("an edit before first publication is applied but announced to nobody")
    void doesNotJournalEditsToAnUnpublishedCourse() {
        // A course that has never been published has never had a learner — enrolment goes through a
        // published-course check on every checkout path — so there is nobody a change row could be
        // read by and nobody a stamp could point at. An instructor iterating on a draft therefore
        // leaves no trace in either, and the first cohort still sees the finished lesson as it
        // stands when they enrol.
        Lesson lesson = storedRichLesson("مسودة");
        java.time.LocalDateTime before = lesson.getContentUpdatedAt();

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, richRequest("مسودة معدّلة"));

        assertThat(lesson.getRichContent()).contains("مسودة معدّلة");
        verify(courseChangeRepository, never()).saveAll(any());
        assertThat(lesson.getContentUpdatedAt()).isEqualTo(before);
    }

    @Test
    @DisplayName("re-saving an unchanged document tells nobody anything")
    void aNoOpSaveAnnouncesNothing() {
        givenAPublishedCourse();
        Lesson lesson = storedRichLesson("نفس المحتوى");
        java.time.LocalDateTime before = lesson.getContentUpdatedAt();

        // The same document, submitted again exactly as the editor would round-trip it. The stored
        // form is canonical, so this has to compare equal — otherwise every instructor who opened a
        // lesson and closed it would announce a new version of the course to everyone enrolled.
        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, richRequest("نفس المحتوى"));

        assertThat(lesson.getContentUpdatedAt()).isEqualTo(before);
        verify(courseChangeRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("a rich-content lesson with an unreadable stored document still describes itself")
    void toleratesAnUnreadableStoredDocument() {
        Lesson lesson = Lesson.builder()
                .id(LESSON_ID)
                .title("Lesson")
                .contentType(LessonContentType.RICH_CONTENT)
                .richContent("{ this is not json")
                .duration(0)
                .orderIndex(0)
                .course(course)
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(lesson));

        // The read path is deliberately lenient — a lesson whose content cannot be read must not
        // take the curriculum down with it.
        assertThatCode(() -> lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID, richRequest("محتوى صالح")))
                .doesNotThrowAnyException();
        assertThat(lesson.getRichContent()).contains("محتوى صالح");
    }

    @SuppressWarnings("unchecked")
    private List<ContentChangeType> recordedChangeTypes() {
        ArgumentCaptor<List<CourseChange>> captor = ArgumentCaptor.forClass(List.class);
        verify(courseChangeRepository).saveAll(captor.capture());
        return captor.getValue().stream().map(CourseChange::getChangeType).toList();
    }
}
