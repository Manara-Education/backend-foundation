package com.manara.backend.lesson.mapper;

import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.dto.ContentChangeResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.model.CompletedLesson;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import com.manara.backend.video.model.ResolvedVideo;
import com.manara.backend.video.service.VideoProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LessonMapper {

    private final DurationFormatter durationFormatter;
    private final VideoProviderResolver videoProviderResolver;

    public Lesson toLesson(LessonRequest request, Course course, CourseModule module, Integer orderIndex) {
        // Resolved, not merely trimmed: an unplayable URL is refused here, before a row exists, and
        // the provider columns are filled from the same parse that accepted it.
        ResolvedVideo video = videoProviderResolver.resolve(request.getVideoUrl(), request.getVideoProvider());

        return Lesson.builder()
                .title(request.getTitle().trim())
                .summary(request.getSummary())
                .description(request.getDescription())
                .video(video.toVideoSource())
                .duration(0)
                .orderIndex(orderIndex)
                .course(course)
                .module(module)
                .build();
    }

    public CompletedLesson toCompletedLesson(Student student, Lesson lesson) {
        return CompletedLesson.builder()
                .student(student)
                .lesson(lesson)
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson) {
        return toLessonResponse(lesson, null, null);
    }

    public LessonResponse toLessonResponse(Lesson lesson, Boolean isCompleted) {
        return toLessonResponse(lesson, isCompleted, null);
    }

    public LessonDetailsResponse.LessonRef toLessonRef(Lesson lesson) {
        if (lesson == null) return null;
        return LessonDetailsResponse.LessonRef.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .build();
    }

    /**
     * Takes an already-built lesson body so the caller decides whether it is the open or the locked
     * one — this mapper never has the context to make that call itself.
     */
    public LessonDetailsResponse toLessonDetailsResponse(LessonResponse lesson, Lesson previous, Lesson next) {
        return LessonDetailsResponse.builder()
                .lesson(lesson)
                .previous(toLessonRef(previous))
                .next(toLessonRef(next))
                .build();
    }

    public LessonResponse toLessonResponse(Lesson lesson, Boolean isCompleted, LearnerQuizResponse quiz) {
        return toLessonResponse(lesson, isCompleted, quiz, null);
    }

    /**
     * @param change what to say about this lesson to the learner reading it, or {@code null} on the
     *               paths where the question has no answer — a single-lesson endpoint, or a visitor
     *               who has not enrolled
     */
    public LessonResponse toLessonResponse(Lesson lesson, Boolean isCompleted, LearnerQuizResponse quiz,
                                           ContentChangeResponse change) {
        LessonResponse.LessonResponseBuilder builder = baseLessonResponse(lesson, isCompleted)
                .locked(false)
                .description(lesson.getDescription())
                .quiz(quiz)
                .change(change);
        return withVideo(builder, lesson).build();
    }

    /**
     * The same lesson with its content withheld: the row still carries what a curriculum listing
     * shows — title, summary, length, position — while the video, the description and the quiz are
     * simply not in the payload. Omitting them rather than blanking them is deliberate; there is no
     * value to leak and nothing for a client to accidentally render.
     */
    public LessonResponse toLockedLessonResponse(Lesson lesson, Boolean isCompleted) {
        return toLockedLessonResponse(lesson, isCompleted, null);
    }

    /**
     * A locked lesson still carries its change state. It is a row in the curriculum the learner can
     * see, and "this lesson is new since you enrolled" is a fact about the listing rather than about
     * the content behind it — withholding it would hide the very thing the badge exists to point at.
     */
    public LessonResponse toLockedLessonResponse(Lesson lesson, Boolean isCompleted,
                                                 ContentChangeResponse change) {
        return baseLessonResponse(lesson, isCompleted)
                .locked(true)
                .change(change)
                .build();
    }

    private LessonResponse.LessonResponseBuilder baseLessonResponse(Lesson lesson, Boolean isCompleted) {
        return LessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .duration(durationFormatter.formatSeconds(lesson.getDuration()))
                .orderIndex(lesson.getOrderIndex())
                .courseId(lesson.getCourse().getId())
                .moduleId(moduleId(lesson))
                .isCompleted(isCompleted)
                .createdAt(lesson.getCreatedAt());
    }

    public InstructorLessonResponse toInstructorLessonResponse(Lesson lesson, InstructorQuizResponse quiz) {
        InstructorLessonResponse.InstructorLessonResponseBuilder builder = InstructorLessonResponse.builder()
                .id(lesson.getId())
                .title(lesson.getTitle())
                .summary(lesson.getSummary())
                .description(lesson.getDescription())
                .duration(durationFormatter.formatSeconds(lesson.getDuration()))
                .orderIndex(lesson.getOrderIndex())
                .courseId(lesson.getCourse().getId())
                .moduleId(moduleId(lesson))
                .quiz(quiz)
                .createdAt(lesson.getCreatedAt());

        builder.videoUrl(videoUrl(lesson));
        videoProviderResolver.describe(lesson.getVideo()).ifPresent(video -> builder
                .videoProvider(video.provider())
                .externalVideoId(video.externalId())
                .videoEmbedUrl(video.embedUrl())
                .videoThumbnailUrl(video.thumbnailUrl()));

        return builder.build();
    }

    /**
     * Attaches the video to a learner response.
     *
     * <p>Split out so the open and locked bodies cannot drift: the locked one simply never calls
     * this, which is what keeps every video field — not just the URL — out of a payload the viewer
     * has not earned.
     *
     * <p>The provider fields are absent, rather than blank, for a URL the resolver cannot read. A
     * client that finds {@code videoUrl} present and {@code videoProvider} null is looking at a
     * video Manara has no player for, and can say so instead of rendering an empty frame.
     */
    private LessonResponse.LessonResponseBuilder withVideo(
            LessonResponse.LessonResponseBuilder builder, Lesson lesson) {

        builder.videoUrl(videoUrl(lesson));
        videoProviderResolver.describe(lesson.getVideo()).ifPresent(video -> builder
                .videoProvider(video.provider())
                .externalVideoId(video.externalId())
                .videoEmbedUrl(video.embedUrl())
                .videoThumbnailUrl(video.thumbnailUrl()));

        return builder;
    }

    private String videoUrl(Lesson lesson) {
        return lesson.getVideo() == null ? null : lesson.getVideo().getUrl();
    }

    private Long moduleId(Lesson lesson) {
        return lesson.getModule() == null ? null : lesson.getModule().getId();
    }
}
