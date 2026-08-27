package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseProgressionCalculator;
import com.manara.backend.course.service.CourseProgressionService;
import com.manara.backend.course.service.CourseViewer;
import com.manara.backend.course.service.LearnerCourseAccess;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.mapper.QuizMapper;
import com.manara.backend.video.VideoProviderFixtures;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.service.VideoMetadataService;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.model.QuizQuestion;
import com.manara.backend.quiz.service.QuizService;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Lesson completion and lesson content, from the learner's side.
 *
 * <p>Two rules are asserted here, and both used to live only in the prototype's component state:
 * a lesson with a quiz cannot be completed until that quiz is passed, and a lesson the learner has
 * not reached does not hand over its video.
 */
@ExtendWith(MockitoExtension.class)
class LessonProgressionTest {

    private static final Long COURSE_ID = 7L;
    private static final Long LESSON_QUIZ_ID = 500L;

    @Mock
    private LessonRepository lessonRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private CourseModuleRepository courseModuleRepository;

    @Mock
    private CompletedLessonRepository completedLessonRepository;

    @Mock
    private EnrollmentRepository enrollmentRepository;

    @Mock
    private LearnerCourseAccess learnerCourseAccess;

    @Mock
    private CourseProgressionService courseProgressionService;

    @Mock
    private QuizService quizService;

    @Mock
    private VideoMetadataService videoMetadataService;

    @Mock
    private MessageService messageService;

    private LessonService lessonService;

    private final CourseProgressionCalculator calculator = new CourseProgressionCalculator();
    private final User user = User.builder().id(2L).role(Role.STUDENT).build();
    private final Student student = Student.builder().id(20L).user(user).build();
    private final Enrollment enrollment = Enrollment.builder().id(40L).student(student).progress(0).build();

    @BeforeEach
    void setUp() {
        DurationFormatter durationFormatter = new DurationFormatter(messageService);
        lessonService = new LessonService(
                lessonRepository, courseRepository, courseModuleRepository, completedLessonRepository,
                enrollmentRepository, learnerCourseAccess, courseProgressionService,
                new LessonMapper(durationFormatter, VideoProviderFixtures.resolver()), quizService,
                new QuizMapper(), videoMetadataService, VideoProviderFixtures.resolver(),
                java.time.Clock.systemUTC());
        lenient().when(messageService.get(any(), any())).thenReturn("0s");
    }

    // --- completion gating ---------------------------------------------------

    @Test
    void aLessonWithoutAQuizCanSimplyBeCompleted() {
        CourseAggregate aggregate = flatCourse(2, false);
        givenEnrolled(aggregate, Set.of(), Map.of());
        givenLesson(aggregate, 1L);
        givenRecomputeFrom(aggregate);

        var response = lessonService.markLessonCompleted(user, COURSE_ID, 1L);

        assertThat(response.getCompleted()).isTrue();
        assertThat(response.getCourseProgress()).isEqualTo(50);
        assertThat(response.getNextLessonId()).isEqualTo(2L);
        assertThat(response.getCourseCompleted()).isFalse();
        verify(completedLessonRepository).save(any(CompletedLesson.class));
    }

    @Test
    void aLessonWithAQuizStaysIncompleteUntilTheQuizIsPassed() {
        CourseAggregate aggregate = flatCourse(2, true);
        givenEnrolled(aggregate, Set.of(), Map.of());
        givenLesson(aggregate, 1L);

        assertThatThrownBy(() -> lessonService.markLessonCompleted(user, COURSE_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.lessonRequiresPass");

        verify(completedLessonRepository, never()).save(any());
        verify(enrollmentRepository, never()).save(any());
    }

    @Test
    void aFailedAttemptDoesNotUnlockCompletionEither() {
        CourseAggregate aggregate = flatCourse(2, true);
        givenEnrolled(aggregate, Set.of(), Map.of(LESSON_QUIZ_ID, List.of(attempt(1L, 40, false))));
        givenLesson(aggregate, 1L);

        assertThatThrownBy(() -> lessonService.markLessonCompleted(user, COURSE_ID, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.quiz.lessonRequiresPass");
    }

    @Test
    void passingTheQuizLetsTheLessonBeCompleted() {
        CourseAggregate aggregate = flatCourse(2, true);
        givenEnrolled(aggregate, Set.of(), Map.of(LESSON_QUIZ_ID, List.of(attempt(1L, 90, true))));
        givenLesson(aggregate, 1L);
        givenRecomputeFrom(aggregate);

        var response = lessonService.markLessonCompleted(user, COURSE_ID, 1L);

        assertThat(response.getCompleted()).isTrue();
        assertThat(response.getCourseProgress()).isEqualTo(50);
    }

    @Test
    void completingTheLastLessonReportsTheFinishedCourseAndNoNextLesson() {
        CourseAggregate aggregate = flatCourse(2, false);
        givenEnrolled(aggregate, Set.of(1L), Map.of());
        givenLesson(aggregate, 2L);
        givenRecomputeFrom(aggregate);

        var response = lessonService.markLessonCompleted(user, COURSE_ID, 2L);

        assertThat(response.getCourseProgress()).isEqualTo(100);
        assertThat(response.getNextLessonId()).isNull();
        assertThat(response.getCourseCompleted()).isTrue();
        assertThat(enrollment.getProgress()).isEqualTo(100);
    }

    @Test
    void completingAlreadyCompletedLessonIsIdempotent() {
        CourseAggregate aggregate = flatCourse(2, false);
        givenEnrolled(aggregate, Set.of(1L), Map.of());
        givenLesson(aggregate, 1L);
        givenRecomputeFrom(aggregate);

        var response = lessonService.markLessonCompleted(user, COURSE_ID, 1L);

        assertThat(response.getCompleted()).isTrue();
        assertThat(response.getCourseProgress()).isEqualTo(50);
        verify(completedLessonRepository, never()).save(any());
    }

    @Test
    void aLessonInsideALockedModuleCannotBeCompleted() {
        CourseAggregate aggregate = twoModules();
        givenEnrolled(aggregate, Set.of(), Map.of());
        givenLesson(aggregate, 3L);

        assertThatThrownBy(() -> lessonService.markLessonCompleted(user, COURSE_ID, 3L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.lesson.locked");
    }

    // --- content protection --------------------------------------------------

    @Test
    void anEnrolledLearnerGetsTheVideoAndTheQuizOfALessonTheyHaveReached() {
        CourseAggregate aggregate = flatCourse(2, true);
        givenViewer(aggregate, calculator.compute(aggregate, Set.of(), Map.of()));

        var response = lessonService.getLesson(user, COURSE_ID, 1L).getLesson();

        assertThat(response.getLocked()).isFalse();
        assertThat(response.getVideoUrl()).isEqualTo("https://youtu.be/" + youTubeId(1));
        // The video arrives described, not just linked: a client knows which player to build
        // without parsing the URL itself.
        assertThat(response.getVideoProvider()).isEqualTo(VideoProvider.YOUTUBE);
        assertThat(response.getExternalVideoId()).isEqualTo(youTubeId(1));
        assertThat(response.getVideoEmbedUrl()).isEqualTo("https://www.youtube.com/embed/" + youTubeId(1));
        assertThat(response.getQuiz()).isNotNull();
        assertThat(response.getQuiz().getState().getAvailable()).isTrue();
    }

    @Test
    void aViewerWhoIsNotEnrolledGetsTheLessonListedButNotItsContent() {
        CourseAggregate aggregate = flatCourse(2, true);
        givenViewer(aggregate, CourseProgression.forVisitor());

        var response = lessonService.getLesson(user, COURSE_ID, 1L).getLesson();

        assertThat(response.getLocked()).isTrue();
        assertThat(response.getTitle()).isEqualTo("Lesson 1");
        assertThat(response.getVideoUrl()).isNull();
        assertThat(response.getDescription()).isNull();
        assertThat(response.getQuiz()).isNull();
    }

    @Test
    void aLessonInsideALockedModuleWithholdsItsContentFromAnEnrolledLearnerToo() {
        CourseAggregate aggregate = twoModules();
        givenViewer(aggregate, calculator.compute(aggregate, Set.of(), Map.of()));

        var open = lessonService.getLesson(user, COURSE_ID, 1L).getLesson();
        var locked = lessonService.getLesson(user, COURSE_ID, 3L).getLesson();

        assertThat(open.getLocked()).isFalse();
        assertThat(open.getVideoUrl()).isNotNull();
        assertThat(locked.getLocked()).isTrue();
        assertThat(locked.getVideoUrl()).isNull();
    }

    @Test
    void theCurriculumListingLocksTheLessonsTheLearnerHasNotReached() {
        CourseAggregate aggregate = twoModules();
        givenViewer(aggregate, calculator.compute(aggregate, Set.of(), Map.of()));

        var lessons = lessonService.getCourseLessons(user, COURSE_ID);

        assertThat(lessons).extracting("locked").containsExactly(false, false, true, true);
        assertThat(lessons).extracting("title")
                .containsExactly("Lesson 1", "Lesson 2", "Lesson 3", "Lesson 4");
    }

    // --- fixtures ------------------------------------------------------------

    private void givenEnrolled(CourseAggregate aggregate, Set<Long> completed, Map<Long, List<QuizAttempt>> attempts) {
        given(learnerCourseAccess.requireEnrolled(user, COURSE_ID)).willReturn(new CourseViewer(
                aggregate.course(), student, enrollment, aggregate,
                calculator.compute(aggregate, completed, attempts)));
    }

    private void givenViewer(CourseAggregate aggregate, CourseProgression progression) {
        given(learnerCourseAccess.resolveViewer(user, COURSE_ID)).willReturn(
                new CourseViewer(aggregate.course(), student, enrollment, aggregate, progression));
    }

    private void givenLesson(CourseAggregate aggregate, Long lessonId) {
        Lesson lesson = aggregate.lessons().stream()
                .filter(l -> l.getId().equals(lessonId))
                .findFirst()
                .orElseThrow();
        given(lessonRepository.findById(lessonId)).willReturn(Optional.of(lesson));
    }

    /** Real rules, re-run over whatever completion set the service hands in. */
    private void givenRecomputeFrom(CourseAggregate aggregate) {
        given(courseProgressionService.recompute(any(), any(), any())).willAnswer(invocation ->
                calculator.compute(aggregate, invocation.getArgument(2), Map.of()));
    }

    private CourseAggregate flatCourse(int lessonCount, boolean firstLessonHasQuiz) {
        Course course = course(CourseStructure.FLAT);
        List<Lesson> lessons = new ArrayList<>();
        for (int i = 1; i <= lessonCount; i++) {
            lessons.add(lesson(i, course, null));
        }
        Map<Long, Quiz> lessonQuizzes = new LinkedHashMap<>();
        if (firstLessonHasQuiz) {
            lessonQuizzes.put(1L, quiz());
        }
        return new CourseAggregate(course, List.of(), lessons, lessonQuizzes, Map.of(), null, List.of());
    }

    /** Two modules of two lessons; the first module has an exam, so the second stays shut. */
    private CourseAggregate twoModules() {
        Course course = course(CourseStructure.MODULES);
        CourseModule first = CourseModule.builder().id(10L).course(course).title("One").orderIndex(0).build();
        CourseModule second = CourseModule.builder().id(20L).course(course).title("Two").orderIndex(1).build();
        List<Lesson> lessons = List.of(
                lesson(1, course, first), lesson(2, course, first),
                lesson(3, course, second), lesson(4, course, second));
        return new CourseAggregate(course, List.of(first, second), lessons, Map.of(),
                Map.of(10L, Quiz.builder().id(100L).ownerType(QuizOwnerType.MODULE).ownerId(10L)
                        .title("Module Exam").passingScore(70).questions(new ArrayList<>()).build()),
                null, List.of());
    }

    private Course course(CourseStructure structure) {
        return Course.builder().id(COURSE_ID).title("Course").structure(structure).build();
    }

    private Lesson lesson(int id, Course course, CourseModule module) {
        return Lesson.builder()
                .id((long) id)
                .title("Lesson " + id)
                .description("Lesson " + id + " notes")
                .video(VideoProviderFixtures.resolver()
                        .resolve("https://youtu.be/" + youTubeId(id)).toVideoSource())
                .course(course)
                .module(module)
                .orderIndex(id)
                .build();
    }

    /** A syntactically real YouTube id — eleven characters — that still identifies the lesson. */
    private String youTubeId(int id) {
        return ("lesson" + id + "AAAAA").substring(0, 11);
    }

    private Quiz quiz() {
        Quiz quiz = Quiz.builder()
                .id(LESSON_QUIZ_ID)
                .ownerType(QuizOwnerType.LESSON)
                .ownerId(1L)
                .title("Lesson Quiz")
                .passingScore(70)
                .questions(new ArrayList<>())
                .build();
        QuizQuestion question = QuizQuestion.builder()
                .id(1L).text("Question").orderIndex(0).options(new ArrayList<>()).build();
        question.addOption(QuizOption.builder().id(10L).text("Wrong").correct(false).orderIndex(0).build());
        question.addOption(QuizOption.builder().id(11L).text("Right").correct(true).orderIndex(1).build());
        quiz.addQuestion(question);
        return quiz;
    }

    private QuizAttempt attempt(Long id, int score, boolean passed) {
        return QuizAttempt.builder()
                .id(id).score(score).passed(passed).passingScore(70)
                .submittedAt(LocalDateTime.now())
                .build();
    }
}
