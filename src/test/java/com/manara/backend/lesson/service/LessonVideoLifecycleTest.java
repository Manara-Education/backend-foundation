package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.repository.CourseChangeRepository;
import com.manara.backend.course.service.CourseContentJournal;
import com.manara.backend.course.service.CourseProgressionService;
import com.manara.backend.course.service.CourseUpdateResolver;
import com.manara.backend.course.service.CourseUpdateWindow;
import com.manara.backend.course.service.LearnerCourseAccess;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.quiz.mapper.QuizMapper;
import com.manara.backend.quiz.service.QuizService;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.lesson.LessonContentFixtures;
import com.manara.backend.video.VideoProviderFixtures;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.service.VideoMetadataService;
import com.manara.backend.video.service.VideoProviderResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The instructor's whole video story, end to end through the service the endpoints call.
 *
 * <p>This is the integration matrix the refactor has to satisfy: a lesson can be created on either
 * platform, edited on either platform, and moved from one to the other in both directions, while a
 * link Manara cannot play is refused before it is stored. The old YouTube lesson case is here too,
 * because "existing courses keep working" is the one requirement that cannot be checked by reading
 * the code.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LessonVideoLifecycleTest {

    private static final Long COURSE_ID = 1L;
    private static final Long LESSON_ID = 10L;

    private static final String YOUTUBE_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String VIMEO_URL = "https://vimeo.com/76979871";

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

    @Mock
    private CourseChangeRepository courseChangeRepository;

    private LessonService lessonService;
    private Course course;

    private final User instructorUser = User.builder().id(5L).role(Role.INSTRUCTOR).build();

    @BeforeEach
    void setUp() {
        VideoProviderResolver resolver = VideoProviderFixtures.resolver();
        DurationFormatter durationFormatter = new DurationFormatter(messageService);

        lessonService = new LessonService(
                lessonRepository, courseRepository, courseModuleRepository, completedLessonRepository,
                enrollmentRepository, learnerCourseAccess, new LessonPlacement(lessonRepository),
                courseProgressionService,
                new CourseContentJournal(courseChangeRepository),
                new LessonMapper(durationFormatter, resolver), quizService, new QuizMapper(),
                videoMetadataService, LessonContentFixtures.validator(), LessonContentFixtures.writer(),
                courseUpdateResolver, java.time.Clock.systemUTC());

        // Not enrolled is the window that reports nothing, which is what every test here that is
        // not about the Updated badge wants: the lesson answers exactly as it always did.
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
        // The authoring paths hold the course row for the transaction; see
        // LessonService#getCourseAndVerifyInstructor.
        given(courseRepository.findByIdForUpdate(COURSE_ID)).willReturn(Optional.of(course));
        given(lessonRepository.saveAndFlush(any(Lesson.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
        given(lessonRepository.save(any(Lesson.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private LessonRequest request(String videoUrl) {
        return LessonRequest.builder().title("Lesson").videoUrl(videoUrl).orderIndex(0).build();
    }

    /** A lesson as the database holds one, on whichever platform. */
    private Lesson storedLesson(String videoUrl) {
        Lesson lesson = Lesson.builder()
                .id(LESSON_ID)
                .title("Lesson")
                .video(VideoProviderFixtures.resolver().resolve(videoUrl).toVideoSource())
                .duration(120)
                .orderIndex(0)
                .course(course)
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(lesson));
        return lesson;
    }

    // --- creating -----------------------------------------------------------

    @Test
    void createsALessonFromAYouTubeLink() {
        LessonResponse response = lessonService.addLesson(instructorUser, COURSE_ID, request(YOUTUBE_URL));

        assertThat(response.getVideoUrl()).isEqualTo(YOUTUBE_URL);
        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.YOUTUBE);
        assertThat(response.getExternalVideoId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(response.getVideoEmbedUrl()).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
        assertThat(response.getVideoThumbnailUrl())
                .isEqualTo("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
    }

    @Test
    void createsALessonFromAVimeoLink() {
        LessonResponse response = lessonService.addLesson(instructorUser, COURSE_ID, request(VIMEO_URL));

        assertThat(response.getVideoUrl()).isEqualTo(VIMEO_URL);
        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.VIMEO);
        assertThat(response.getExternalVideoId()).isEqualTo("76979871");
        assertThat(response.getVideoEmbedUrl()).isEqualTo("https://player.vimeo.com/video/76979871");
    }

    /** Both platforms schedule the same lookup, which is what keeps duration provider-independent. */
    @ParameterizedTest
    @ValueSource(strings = {YOUTUBE_URL, VIMEO_URL})
    void schedulesAMetadataRefreshWhicheverPlatformItIs(String url) {
        lessonService.addLesson(instructorUser, COURSE_ID, request(url));

        verify(videoMetadataService).refreshAsync(any(), any(VideoSource.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://dailymotion.com/video/x8abcd",
            "https://example.com/lesson.mp4",
            "not a url",
            "https://youtube.com/watch?v=short",
    })
    void refusesALinkItCannotPlayWithoutWritingAnything(String url) {
        assertThatThrownBy(() -> lessonService.addLesson(instructorUser, COURSE_ID, request(url)))
                .isInstanceOf(BusinessException.class);

        verify(lessonRepository, never()).saveAndFlush(any(Lesson.class));
        verify(videoMetadataService, never()).refreshAsync(any(), any());
    }

    /**
     * A client that volunteers the wrong provider is turned away rather than believed, so the
     * provider column can never describe a different platform from the URL beside it.
     */
    @Test
    void refusesAProviderThatContradictsTheLink() {
        LessonRequest request = request(VIMEO_URL);
        request.setVideoProvider(VideoProvider.YOUTUBE);

        assertThatThrownBy(() -> lessonService.addLesson(instructorUser, COURSE_ID, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.video.providerMismatch");

        verify(lessonRepository, never()).saveAndFlush(any(Lesson.class));
    }

    // --- editing ------------------------------------------------------------

    @Test
    void movesALessonFromYouTubeToVimeo() {
        Lesson lesson = storedLesson(YOUTUBE_URL);

        LessonResponse response = lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID, request(VIMEO_URL));

        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.VIMEO);
        assertThat(response.getVideoUrl()).isEqualTo(VIMEO_URL);
        assertThat(lesson.getVideo().getProvider()).isEqualTo(VideoProvider.VIMEO);
        assertThat(lesson.getVideo().getExternalId()).isEqualTo("76979871");
        // The old platform's running time does not belong to the new video.
        assertThat(lesson.getDuration()).isZero();
        verify(videoMetadataService).refreshAsync(eq(LESSON_ID), any(VideoSource.class));
    }

    @Test
    void movesALessonFromVimeoToYouTube() {
        Lesson lesson = storedLesson(VIMEO_URL);

        LessonResponse response = lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID, request(YOUTUBE_URL));

        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.YOUTUBE);
        assertThat(lesson.getVideo().getProvider()).isEqualTo(VideoProvider.YOUTUBE);
        assertThat(lesson.getVideo().getExternalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(lesson.getDuration()).isZero();
    }

    /** Editing the title alone must not cost the lesson the duration it already has. */
    @Test
    void keepsTheDurationWhenTheVideoDidNotChange() {
        Lesson lesson = storedLesson(YOUTUBE_URL);
        LessonRequest request = request(YOUTUBE_URL);
        request.setTitle("A better title");

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, request);

        assertThat(lesson.getDuration()).isEqualTo(120);
        verify(videoMetadataService, never()).refreshAsync(any(), any());
    }

    @Test
    void refusesAnEditThatWouldLeaveTheLessonUnplayable() {
        Lesson lesson = storedLesson(YOUTUBE_URL);

        assertThatThrownBy(() -> lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID, request("https://example.com/video.mp4")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.video.providerUnsupported");

        // Refused before anything was applied: the lesson still plays what it played before.
        assertThat(lesson.getVideo().getUrl()).isEqualTo(YOUTUBE_URL);
        assertThat(lesson.getDuration()).isEqualTo(120);
    }

    // --- lessons that predate providers -------------------------------------

    /**
     * A row exactly as the prototype left it — a URL, and none of the columns this change adds.
     * It has to describe itself fully on read, with no migration having been run.
     */
    @Test
    void describesALegacyRowThatCarriesOnlyAUrl() {
        Lesson legacy = Lesson.builder()
                .id(LESSON_ID)
                .title("Old lesson")
                .video(VideoSource.ofUrl("https://www.youtube.com/watch?v=Jc__iOQgQNM"))
                .duration(2700)
                .orderIndex(0)
                .course(course)
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(legacy));

        LessonResponse response = lessonService.updateLesson(
                instructorUser, COURSE_ID, LESSON_ID,
                request("https://www.youtube.com/watch?v=Jc__iOQgQNM"));

        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.YOUTUBE);
        assertThat(response.getExternalVideoId()).isEqualTo("Jc__iOQgQNM");
        // Untouched video, so the duration it already had survives the edit.
        assertThat(legacy.getDuration()).isEqualTo(2700);
    }

    /** Saving one back-fills its provider columns, with no separate migration pass. */
    @Test
    void backFillsTheProviderColumnsOfALegacyRowOnItsNextSave() {
        Lesson legacy = Lesson.builder()
                .id(LESSON_ID)
                .title("Old lesson")
                .video(VideoSource.ofUrl(YOUTUBE_URL))
                .duration(2700)
                .orderIndex(0)
                .course(course)
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(legacy));

        assertThat(legacy.getVideo().getProvider()).isNull();

        lessonService.updateLesson(instructorUser, COURSE_ID, LESSON_ID, request(YOUTUBE_URL));

        assertThat(legacy.getVideo().getProvider()).isEqualTo(VideoProvider.YOUTUBE);
        assertThat(legacy.getVideo().getExternalId()).isEqualTo("dQw4w9WgXcQ");
        assertThat(legacy.getVideo().getThumbnailUrl())
                .isEqualTo("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
    }
}
