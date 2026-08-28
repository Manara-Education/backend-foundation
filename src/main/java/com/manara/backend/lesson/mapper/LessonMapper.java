package com.manara.backend.lesson.mapper;

import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.dto.ContentChangeResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.lesson.dto.LessonDetailsResponse;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.lesson.validation.LessonContent;
import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import com.manara.backend.video.service.VideoProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LessonMapper {

    private final DurationFormatter durationFormatter;
    private final VideoProviderResolver videoProviderResolver;

    /**
     * Builds a new lesson from content the caller has already validated.
     *
     * <p>Takes {@link LessonContent} rather than resolving a video itself, which is what stops the
     * create path from having its own opinion about what a lesson must carry. A rich-content lesson
     * gets no video and a video lesson gets no document — the branch that is not used is left null
     * rather than filled from whatever the payload happened to have in it.
     */
    public Lesson toLesson(LessonRequest request, LessonContent content, Course course,
                           CourseModule module, Integer orderIndex) {
        return Lesson.builder()
                .title(request.getTitle().trim())
                .summary(request.getSummary())
                .description(request.getDescription())
                .contentType(content.type())
                .video(content.isVideo() ? content.video().toVideoSource() : null)
                .richContent(content.richContent())
                .duration(0)
                .orderIndex(orderIndex)
                .course(course)
                .module(module)
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
        return withContent(builder, lesson).build();
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
                // On the locked row too. Which kind of lesson it is is part of the listing, not part
                // of the content behind it: a curriculum has to draw a rich-content row without a
                // duration and a video row with one, whether or not the viewer may open either.
                .contentType(contentTypeOf(lesson))
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
                .contentType(contentTypeOf(lesson))
                // The authoring view carries both branches whatever the type is, so reopening the
                // editor on a lesson that was switched to the other kind still shows what the
                // instructor wrote before — the retention the storage policy promises is only real
                // if the editor can see it.
                .richContent(lesson.getRichContent())
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
     * Attaches whichever content this lesson actually has to a learner response.
     *
     * <p>Split out so the open and locked bodies cannot drift: the locked one simply never calls
     * this, which is what keeps every content field — video and document alike — out of a payload
     * the viewer has not earned.
     *
     * <p>The two branches are exclusive by construction. A rich-content lesson is served with no
     * video fields at all, not with empty ones, so a client has nothing to render a player around
     * even by accident — which is the difference between "no video shown" and "an empty video
     * container shown". A lesson that once had a video and was switched still holds its URL in the
     * database; it is simply not part of this answer.
     *
     * <p>For a video lesson the provider fields are absent, rather than blank, for a URL the
     * resolver cannot read. A client that finds {@code videoUrl} present and {@code videoProvider}
     * null is looking at a video Manara has no player for, and can say so instead of rendering an
     * empty frame.
     */
    private LessonResponse.LessonResponseBuilder withContent(
            LessonResponse.LessonResponseBuilder builder, Lesson lesson) {

        if (contentTypeOf(lesson) == LessonContentType.RICH_CONTENT) {
            return builder.richContent(lesson.getRichContent());
        }

        builder.videoUrl(videoUrl(lesson));
        videoProviderResolver.describe(lesson.getVideo()).ifPresent(video -> builder
                .videoProvider(video.provider())
                .externalVideoId(video.externalId())
                .videoEmbedUrl(video.embedUrl())
                .videoThumbnailUrl(video.thumbnailUrl()));

        return builder;
    }

    /**
     * A lesson's type, treating an absent one as {@code VIDEO}.
     *
     * <p>The column is {@code NOT NULL} with a default, so this cannot be null for a row that has
     * been through the database. It can be for one built in a test or held in memory before its
     * first save, and every one of those is a video lesson — which is what the schema default says
     * too.
     */
    private LessonContentType contentTypeOf(Lesson lesson) {
        return lesson.getContentType() == null ? LessonContentType.VIDEO : lesson.getContentType();
    }

    private String videoUrl(Lesson lesson) {
        return lesson.getVideo() == null ? null : lesson.getVideo().getUrl();
    }

    private Long moduleId(Lesson lesson) {
        return lesson.getModule() == null ? null : lesson.getModule().getId();
    }
}
