package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.mapper.CourseModuleMapper;
import com.manara.backend.course.mapper.SubscriptionPlanMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.video.VideoProviderFixtures;
import com.manara.backend.video.service.VideoMetadataService;
import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.service.QuizService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The synchronizer is where a course update can go wrong in the ways that matter: duplicating
 * content, orphaning rows, or letting one instructor's payload reach into another's course.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CourseContentSynchronizerTest {

    private static final Long COURSE_ID = 1L;

    @Mock
    private CourseModuleRepository courseModuleRepository;
    @Mock
    private LessonRepository lessonRepository;
    @Mock
    private CompletedLessonRepository completedLessonRepository;
    @Mock
    private SubscriptionPlanRepository subscriptionPlanRepository;
    @Mock
    private CourseEntitlementRepository courseEntitlementRepository;
    @Mock
    private CourseSubscriptionRepository courseSubscriptionRepository;
    @Mock
    private QuizService quizService;
    @Mock
    private VideoMetadataService videoMetadataService;
    @Mock
    private DurationFormatter durationFormatter;

    private CourseContentSynchronizer synchronizer;
    private Course course;

    @BeforeEach
    void setUp() {
        synchronizer = new CourseContentSynchronizer(
                courseModuleRepository,
                lessonRepository,
                completedLessonRepository,
                subscriptionPlanRepository,
                courseEntitlementRepository,
                courseSubscriptionRepository,
                java.time.Clock.systemUTC(),
                new CourseModuleMapper(),
                new LessonMapper(durationFormatter, VideoProviderFixtures.resolver()),
                new SubscriptionPlanMapper(),
                quizService,
                videoMetadataService,
                VideoProviderFixtures.resolver());

        course = Course.builder()
                .id(COURSE_ID)
                .title("Course")
                .structure(CourseStructure.FLAT)
                .status(CourseStatus.DRAFT)
                .accessType(CourseAccessType.FREE)
                .build();

        // The synchronizer now reads whether a quiz sync changed anything, so the mock has to
        // answer with a result rather than null. "Nothing changed" is the neutral default; the
        // tests that care about change detection say so themselves.
        given(quizService.sync(any(), any(), any()))
                .willReturn(new com.manara.backend.quiz.service.QuizSyncResult(null, null));
        given(lessonRepository.save(any(Lesson.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(courseModuleRepository.save(any(CourseModule.class))).willAnswer(invocation -> invocation.getArgument(0));
        given(subscriptionPlanRepository.findByCourseIdOrderByOrderIndexAsc(COURSE_ID)).willReturn(List.of());
        given(courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(COURSE_ID)).willReturn(List.of());
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of());
    }

    @Test
    void refusesALessonIdThatIsNotOneOfThisCoursesLessons() {
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(lesson(10L, null, 0)));

        // 999 exists — it just belongs to somebody else's course, so it was never in the lookup map.
        CourseRequest request = flatCourse(lessonRequest(999L));

        assertThatThrownBy(() -> synchronizer.sync(course, request, flatSettings(), new CourseContentChanges()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.lessonNotInCourse");
    }

    @Test
    void refusesAModuleIdThatIsNotOneOfThisCoursesModules() {
        course.setStructure(CourseStructure.MODULES);
        CourseRequest request = CourseRequest.builder()
                .title("Course")
                .structure(CourseStructure.MODULES)
                .modules(List.of(ModuleRequest.builder().id(999L).title("Hijacked").lessons(List.of()).build()))
                .build();

        assertThatThrownBy(() -> synchronizer.sync(course, request, modulesSettings(), new CourseContentChanges()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.moduleNotInCourse");
    }

    @Test
    void refusesTheSameLessonTwiceInOnePayload() {
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(lesson(10L, null, 0)));

        CourseRequest request = flatCourse(lessonRequest(10L), lessonRequest(10L));

        assertThatThrownBy(() -> synchronizer.sync(course, request, flatSettings(), new CourseContentChanges()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.course.lessonDuplicate");
    }

    @Test
    void updatesAnExistingLessonInPlaceRatherThanAppendingACopy() {
        Lesson existing = lesson(10L, null, 0);
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(existing));

        LessonRequest update = lessonRequest(10L);
        update.setTitle("Renamed lesson");

        synchronizer.sync(course, flatCourse(update), flatSettings(), new CourseContentChanges());

        assertThat(existing.getTitle()).isEqualTo("Renamed lesson");
        verify(lessonRepository, never()).save(any(Lesson.class));
        verify(lessonRepository, never()).deleteAll(any());
    }

    @Test
    void deletesDroppedLessonsWithTheirProgressRowsAndTheirQuiz() {
        Lesson kept = lesson(10L, null, 0);
        Lesson dropped = lesson(11L, null, 1);
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(kept, dropped));

        synchronizer.sync(course, flatCourse(lessonRequest(10L)), flatSettings(), new CourseContentChanges());

        // No foreign key would have cleaned the quiz up, and one would have blocked the lesson
        // delete until the completion rows were gone — both are this method's responsibility.
        verify(quizService).deleteByOwners(QuizOwnerType.LESSON, List.of(11L));
        verify(completedLessonRepository).deleteByLessonIdIn(List.of(11L));
        verify(lessonRepository).deleteAll(List.of(dropped));
    }

    @Test
    void switchingToFlatReparentsTheLessonsTheEditorKeptAndRemovesTheModules() {
        CourseModule module = module(20L, 0);
        Lesson lessonUnderModule = lesson(10L, module, 0);
        course.setStructure(CourseStructure.MODULES);
        given(courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(COURSE_ID)).willReturn(List.of(module));
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(lessonUnderModule));

        synchronizer.sync(course, flatCourse(lessonRequest(10L)), flatSettings(), new CourseContentChanges());

        assertThat(lessonUnderModule.getModule()).isNull();
        verify(lessonRepository, never()).deleteAll(any());
        verify(quizService).deleteByOwners(QuizOwnerType.MODULE, List.of(20L));
        verify(courseModuleRepository).deleteAll(List.of(module));
    }

    @Test
    void aMetadataOnlyUpdateLeavesTheContentTreeUntouched() {
        // The previous course update sent no lessons at all; treating that as "remove everything"
        // would wipe a whole course for any client that has not been updated yet.
        CourseRequest request = CourseRequest.builder().title("Renamed").description("Still the same course").build();

        synchronizer.sync(course, request, flatSettings(), new CourseContentChanges());

        verify(lessonRepository, never()).findCourseLessonsInReadingOrder(any());
        verify(lessonRepository, never()).deleteAll(any());
        verify(courseModuleRepository, never()).deleteAll(any());
        verify(quizService, never()).sync(any(), any(), any());
    }

    @Test
    void anEmptyLessonArrayIsAnExplicitRequestToRemoveEverything() {
        Lesson existing = lesson(10L, null, 0);
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(existing));

        synchronizer.sync(course, flatCourse(), flatSettings(), new CourseContentChanges());

        verify(lessonRepository).deleteAll(List.of(existing));
    }

    @Test
    void attachesEachQuizToItsOwnOwner() {
        Lesson existing = lesson(10L, null, 0);
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(existing));

        LessonRequest lessonWithQuiz = lessonRequest(10L);
        lessonWithQuiz.setQuiz(quiz("Lesson Quiz"));

        CourseRequest request = flatCourse(lessonWithQuiz);
        request.setFinalQuiz(quiz("Final Exam"));

        synchronizer.sync(course, request, flatSettings(), new CourseContentChanges());

        verify(quizService).sync(eq(QuizOwnerType.LESSON), eq(10L), any(QuizRequest.class));
        verify(quizService).sync(eq(QuizOwnerType.COURSE), eq(COURSE_ID), any(QuizRequest.class));
    }

    @Test
    void keepsTheStoredOrderOfExistingLessonsWhateverOrderThePayloadIsIn() {
        Lesson first = lesson(10L, null, 0);
        Lesson second = lesson(11L, null, 1);
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(first, second));

        // Submitted back to front. An aggregate save carries the whole course, so its arrays are
        // only as fresh as the tab that built them — order comes from the reorder commands now.
        synchronizer.sync(course, flatCourse(lessonRequest(11L), lessonRequest(10L)), flatSettings(), new CourseContentChanges());

        assertThat(first.getOrderIndex()).isZero();
        assertThat(second.getOrderIndex()).isEqualTo(1);
    }

    @Test
    void reorderingNothingRecordsNoContentChange() {
        Lesson first = lesson(10L, null, 0);
        Lesson second = lesson(11L, null, 1);
        given(lessonRepository.findCourseLessonsInReadingOrder(COURSE_ID)).willReturn(List.of(first, second));

        // Echoed back exactly as stored, apart from the shuffle, so the only thing this payload
        // could possibly be reporting is a reorder.
        var changes = new CourseContentChanges();
        synchronizer.sync(course, flatCourse(echoOf(second), echoOf(first)), flatSettings(), changes);

        // The shuffled array is ignored rather than applied, so there is nothing to announce.
        assertThat(changes.hasChanges()).isFalse();
    }

    // --- fixtures -----------------------------------------------------------

    private ResolvedCourseSettings flatSettings() {
        return new ResolvedCourseSettings(
                CourseStructure.FLAT, CourseStatus.DRAFT, CourseAccessType.FREE, null);
    }

    private ResolvedCourseSettings modulesSettings() {
        return new ResolvedCourseSettings(
                CourseStructure.MODULES, CourseStatus.DRAFT, CourseAccessType.FREE, null);
    }

    private CourseRequest flatCourse(LessonRequest... lessons) {
        return CourseRequest.builder()
                .title("Course")
                .description("Description")
                .structure(CourseStructure.FLAT)
                .lessons(List.of(lessons))
                .build();
    }

    /** The request a client would send back for a lesson it has not touched. */
    private LessonRequest echoOf(Lesson lesson) {
        return LessonRequest.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .videoUrl(lesson.getVideo().getUrl())
                .build();
    }

    private LessonRequest lessonRequest(Long id) {
        return LessonRequest.builder()
                .id(id)
                .title("Lesson")
                .videoUrl("https://youtube.com/watch?v=aBcDeFgHiJk")
                .build();
    }

    private Lesson lesson(Long id, CourseModule module, int orderIndex) {
        return Lesson.builder()
                .id(id)
                .title("Lesson " + id)
                .video(VideoProviderFixtures.resolver()
                        .resolve("https://youtube.com/watch?v=aBcDeFgHiJk").toVideoSource())
                .orderIndex(orderIndex)
                .duration(0)
                .course(course)
                .module(module)
                .build();
    }

    private CourseModule module(Long id, int orderIndex) {
        return CourseModule.builder()
                .id(id)
                .title("Module " + id)
                .orderIndex(orderIndex)
                .course(course)
                .build();
    }

    private QuizRequest quiz(String title) {
        return QuizRequest.builder()
                .title(title)
                .passingScore(70)
                .questions(List.of(QuizQuestionRequest.builder()
                        .text("Question")
                        .correctOptionId("a")
                        .options(List.of(
                                QuizOptionRequest.builder().id("a").text("A").build(),
                                QuizOptionRequest.builder().id("b").text("B").build()))
                        .build()))
                .build();
    }
}
