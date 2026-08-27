package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.CourseContentChanges;
import com.manara.backend.course.service.CourseContentJournal;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseProgressionService;
import com.manara.backend.course.service.CourseViewer;
import com.manara.backend.course.service.LearnerCourseAccess;
import com.manara.backend.lesson.dto.LessonCompletionResponse;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.quiz.mapper.QuizMapper;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.service.QuizService;
import com.manara.backend.user.model.Role;
import com.manara.backend.video.model.ResolvedVideo;
import com.manara.backend.video.service.VideoMetadataService;
import com.manara.backend.video.service.VideoProviderResolver;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Lesson-scoped operations.
 *
 * <p>These endpoints edit one lesson at a time; the whole content tree is edited through the course
 * aggregate API. Both go through the same quiz domain service — a lesson quiz saved here is the
 * same row, validated the same way, as one saved through a course payload.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LessonService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final CourseModuleRepository courseModuleRepository;
    private final CompletedLessonRepository completedLessonRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LearnerCourseAccess learnerCourseAccess;
    private final CourseProgressionService courseProgressionService;
    private final CourseContentJournal courseContentJournal;
    private final LessonMapper lessonMapper;
    private final QuizService quizService;
    private final QuizMapper quizMapper;
    private final VideoMetadataService videoMetadataService;
    private final VideoProviderResolver videoProviderResolver;
    private final Clock clock;

    private Course getCourseAndVerifyInstructor(User user, Long courseId) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        Course course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }
        return course;
    }

    /**
     * Resolves the module a lesson should sit under, refusing anything that does not belong to this
     * course — a module id from another instructor's course is rejected here, not trusted.
     */
    private CourseModule resolveModule(Course course, Long moduleId) {
        if (course.getStructure() != CourseStructure.MODULES) {
            if (moduleId != null) {
                throw new BusinessException("error.course.flatLessonWithModule");
            }
            return null;
        }
        if (moduleId == null) {
            throw new BusinessException("error.course.lessonModuleRequired");
        }
        CourseModule module = courseModuleRepository.findById(moduleId)
                .orElseThrow(() -> new BusinessException("error.course.moduleNotInCourse", moduleId));
        if (!module.getCourse().getId().equals(course.getId())) {
            throw new BusinessException("error.course.moduleNotInCourse", moduleId);
        }
        return module;
    }

    private void recalculateCourseDuration(Course course) {
        lessonRepository.flush();
        Integer newDuration = lessonRepository.sumDurationByCourseId(course.getId());
        course.setDuration(newDuration);
        courseRepository.save(course);
    }

    @Transactional
    public LessonResponse addLesson(User user, Long courseId, LessonRequest request) {
        Course course = getCourseAndVerifyInstructor(user, courseId);
        CourseModule module = resolveModule(course, request.getModuleId());

        Lesson lesson = lessonMapper.toLesson(request, course, module, request.getOrderIndex());
        lesson = lessonRepository.saveAndFlush(lesson);

        Quiz quiz = syncQuizIfProvided(lesson, request);

        videoMetadataService.refreshAsync(lesson.getId(), lesson.getVideo());

        // A new lesson is new content by definition, whichever endpoint created it. Learners of a
        // published course have to be told the same thing whether the instructor used the course
        // editor or this endpoint — and the lesson itself has to carry the same "new" state, so a
        // learner is pointed at the row that appeared rather than at the course in general.
        var changes = new CourseContentChanges();
        changes.of(lesson).created();
        if (quiz != null) {
            changes.of(quiz).created();
        }
        commitContentChanges(course, changes);

        return lessonMapper.toLessonResponse(lesson, null, quizMapper.toLearnerResponse(quiz));
    }

    @Transactional
    public LessonResponse updateLesson(User user, Long courseId, Long lessonId, LessonRequest request) {
        Course course = getCourseAndVerifyInstructor(user, courseId);
        Lesson lesson = requireLessonOfCourse(courseId, lessonId);
        CourseModule module = resolveModule(course, request.getModuleId());

        // Resolved before anything is written, so an edit that swaps in an unplayable URL is
        // refused with the lesson untouched rather than half-applied.
        ResolvedVideo video = videoProviderResolver.resolve(request.getVideoUrl(), request.getVideoProvider());
        boolean videoUrlChanged = !video.url().equals(lesson.getVideo().getUrl());

        // Compared before assigned throughout, so a form re-submitted unchanged does not announce a
        // new version of the course to everyone enrolled in it.
        var changes = new CourseContentChanges();
        changes.of(lesson)
                .metadata(lesson.getTitle(), request.getTitle(), lesson::setTitle)
                .metadata(lesson.getSummary(), request.getSummary(), lesson::setSummary)
                .content(lesson.getDescription(), request.getDescription(), lesson::setDescription)
                .reordered(lesson.getOrderIndex(), request.getOrderIndex(), lesson::setOrderIndex);

        Long currentModuleId = lesson.getModule() == null ? null : lesson.getModule().getId();
        Long nextModuleId = module == null ? null : module.getId();
        if (!java.util.Objects.equals(currentModuleId, nextModuleId)) {
            // Read before the write: the module a lesson came from is the only fact about a move
            // that no longer exists once it has been made.
            String from = lesson.getModule() == null ? null : lesson.getModule().getTitle();
            lesson.setModule(module);
            changes.of(lesson).moved(from);
        }

        // Rewritten on every save, not only when the URL changed: a lesson stored before providers
        // existed picks up its provider, id and thumbnail the first time it is edited, with no
        // migration and no separate back-fill pass. A still that had to be fetched is carried over
        // rather than thrown away — see ResolvedVideo#toVideoSource(VideoSource).
        changes.of(lesson).content(lesson.getVideo(), video.toVideoSource(lesson.getVideo()), lesson::setVideo);

        if (videoUrlChanged) {
            lesson.setDuration(0);
        }

        lesson = lessonRepository.save(lesson);

        Quiz quiz = syncQuizIfProvided(lesson, request, changes);

        if (videoUrlChanged) {
            videoMetadataService.refreshAsync(lesson.getId(), lesson.getVideo());
        } else {
            recalculateCourseDuration(lesson.getCourse());
        }

        commitContentChanges(course, changes);

        return lessonMapper.toLessonResponse(lesson, null, quizMapper.toLearnerResponse(quiz));
    }

    @Transactional
    public void deleteLesson(User user, Long courseId, Long lessonId) {
        getCourseAndVerifyInstructor(user, courseId);
        Lesson lesson = requireLessonOfCourse(courseId, lessonId);

        Course course = lesson.getCourse();
        // Recorded before the delete, while there is still something to read a title off. This row
        // is what stops a learner's curriculum quietly losing a lesson between two visits.
        var changes = new CourseContentChanges();
        changes.of(lesson).removed();

        // The quiz owner reference is polymorphic and carries no foreign key, so its cleanup is
        // this method's responsibility — nothing in the database would do it.
        quizService.deleteByOwner(QuizOwnerType.LESSON, lessonId);
        completedLessonRepository.deleteByLessonId(lessonId);
        lessonRepository.delete(lesson);
        recalculateCourseDuration(course);
        commitContentChanges(course, changes);
    }

    /**
     * Records what this request changed, in the caller's transaction.
     *
     * <p>These endpoints edit one lesson, but a lesson is course content, so the course's version
     * has to move with it — otherwise an instructor who adds a lesson here rather than through the
     * course editor changes what learners see without any of them being told.
     *
     * <p>Through the same {@link CourseContentJournal} the course editor uses, so a lesson edited
     * from either surface produces the same timestamps and the same log row. Two paths writing the
     * signal two ways is how the same edit ends up described differently depending on which screen
     * made it.
     */
    private void commitContentChanges(Course course, CourseContentChanges changes) {
        if (courseContentJournal.commit(course, changes, LocalDateTime.now(clock))) {
            courseRepository.save(course);
        }
    }

    public LessonDetailsResponse getLesson(User user, Long courseId, Long lessonId) {
        CourseViewer viewer = learnerCourseAccess.resolveViewer(user, courseId);

        // Reading order spans modules, so "previous" and "next" walk the course the way a learner
        // actually sees it rather than jumping between modules.
        List<Lesson> lessons = viewer.aggregate().lessons();

        int index = indexOf(lessons, lessonId);
        if (index == -1) {
            if (!lessonRepository.existsById(lessonId)) {
                throw new ResourceNotFoundException("error.lesson.notFound", lessonId.toString());
            }
            throw new BusinessException("error.lesson.notInCourse");
        }

        Lesson lesson = lessons.get(index);
        Lesson previous = index > 0 ? lessons.get(index - 1) : null;
        Lesson next = index < lessons.size() - 1 ? lessons.get(index + 1) : null;

        return lessonMapper.toLessonDetailsResponse(learnerLessonResponse(viewer, lesson), previous, next);
    }

    public List<LessonResponse> getCourseLessons(User user, Long courseId) {
        CourseViewer viewer = learnerCourseAccess.resolveViewer(user, courseId);
        return viewer.aggregate().lessons().stream()
                .map(lesson -> learnerLessonResponse(viewer, lesson))
                .toList();
    }

    /**
     * A published course is a shop window, not an open library. Until this change any signed-in
     * user could read a published lesson — video URL, description and all — simply by asking for
     * it, which made enrolment decorative. The listing still shows what a course contains; the
     * content behind it is served only to someone the curriculum has actually opened it for.
     */
    private LessonResponse learnerLessonResponse(CourseViewer viewer, Lesson lesson) {
        CourseProgression progression = viewer.progression();
        if (!progression.isLessonAccessible(lesson)) {
            return lessonMapper.toLockedLessonResponse(lesson, progression.completionOf(lesson));
        }

        Quiz quiz = viewer.aggregate().quizOfLesson(lesson);
        return lessonMapper.toLessonResponse(
                lesson,
                progression.completionOf(lesson),
                quizMapper.toLearnerResponse(quiz, progression.stateOf(quiz)));
    }

    /**
     * Completing a lesson is the learner's claim; whether it counts is the server's decision.
     *
     * <p>A lesson carrying a quiz stays incomplete until that quiz is passed — the prototype
     * enforced this in the player alone, which meant a direct call to this endpoint skipped it
     * entirely. The response reports what the completion changed, so the client updates the
     * curriculum from the server's answer instead of recomputing progress and unlocks itself.
     */
    @Transactional
    public LessonCompletionResponse markLessonCompleted(User user, Long courseId, Long lessonId) {
        CourseViewer viewer = learnerCourseAccess.requireEnrolled(user, courseId);
        Lesson lesson = requireLessonOfCourse(courseId, lessonId);

        if (!viewer.progression().isLessonAccessible(lesson)) {
            throw new BusinessException("error.lesson.locked");
        }

        Quiz quiz = viewer.aggregate().quizOfLesson(lesson);
        if (quiz != null && !viewer.progression().stateOf(quiz).passed()) {
            throw new BusinessException("error.quiz.lessonRequiresPass");
        }

        Set<Long> completedLessonIds = new HashSet<>(viewer.progression().completedLessonIds());
        if (completedLessonIds.add(lessonId)) {
            completedLessonRepository.save(lessonMapper.toCompletedLesson(viewer.student(), lesson));
        }

        // Recomputed from the updated picture rather than read back: the completion above is not
        // flushed yet, and the rules are pure, so handing them the new set is both cheaper and
        // exactly what the next request would see.
        CourseProgression updated = courseProgressionService.recompute(
                viewer.aggregate(), viewer.student(), completedLessonIds);

        viewer.enrollment().setProgress(updated.progress());
        enrollmentRepository.save(viewer.enrollment());

        return LessonCompletionResponse.builder()
                .lessonId(lessonId)
                .completed(true)
                .courseProgress(updated.progress())
                .nextLessonId(updated.nextLessonId())
                .courseCompleted(updated.courseCompleted())
                .build();
    }

    /**
     * These endpoints edit one lesson; they are not the course editor. A payload that says nothing
     * about a quiz therefore leaves the existing one alone rather than deleting it — clients that
     * predate quizzes send exactly that. Removing a lesson quiz is done through the course
     * aggregate, whose payload is a deliberate full replacement.
     */
    private Quiz syncQuizIfProvided(Lesson lesson, LessonRequest request) {
        return syncQuizIfProvided(lesson, request, new CourseContentChanges());
    }

    private Quiz syncQuizIfProvided(Lesson lesson, LessonRequest request, CourseContentChanges changes) {
        if (request.getQuiz() == null) {
            return quizService.findByOwner(QuizOwnerType.LESSON, lesson.getId()).orElse(null);
        }
        var result = quizService.sync(QuizOwnerType.LESSON, lesson.getId(), request.getQuiz());
        if (result.quiz() != null && result.changed()) {
            // Against the quiz, not the lesson: editing a lesson's questions is a change to its
            // quiz, and marking the lesson updated for it would point the learner at a video that
            // has not moved.
            changes.of(result.quiz()).recordIf(true, result.outcome());
        }
        return result.quiz();
    }

    private Lesson requireLessonOfCourse(Long courseId, Long lessonId) {
        Lesson lesson = lessonRepository.findById(lessonId)
                .orElseThrow(() -> new ResourceNotFoundException("error.lesson.notFound", lessonId.toString()));
        if (!lesson.getCourse().getId().equals(courseId)) {
            throw new BusinessException("error.lesson.notInCourse");
        }
        return lesson;
    }

    private int indexOf(List<Lesson> lessons, Long lessonId) {
        for (int i = 0; i < lessons.size(); i++) {
            if (lessons.get(i).getId().equals(lessonId)) {
                return i;
            }
        }
        return -1;
    }
}
