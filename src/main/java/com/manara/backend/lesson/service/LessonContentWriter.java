package com.manara.backend.lesson.service;

import com.manara.backend.course.service.CourseContentChanges;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.lesson.validation.LessonContent;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.service.VideoProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies validated content to an existing lesson, and says what that change means.
 *
 * <p>Written once and shared by both authoring surfaces — the standalone lesson endpoints and the
 * course aggregate save — because they were already two copies of the video-writing rules and this
 * feature would have made them two copies of considerably more. A lesson edited through the course
 * editor and the same lesson edited through the lesson API now produce the same columns, the same
 * change-log entry and the same decision about whether to re-measure the video.
 *
 * <h2>Retention, not replacement</h2>
 * The branch a lesson is not currently using is never written and never cleared. Switching a lesson
 * to {@code RICH_CONTENT} leaves its video URL, provider and thumbnail exactly where they are;
 * switching it back restores the lesson it was, with no confirmation needed and nothing to undo.
 * The alternative — clearing the inactive branch on save — makes a type change a destructive
 * operation, and a destructive operation that a mis-click performs silently.
 *
 * <p>The consequence worth stating: {@code video_url} on a rich-content row is not stale data to be
 * cleaned up. It is the lesson's previous life, kept deliberately, and
 * {@link com.manara.backend.lesson.model.LessonContentType} is what stops anything reading it.
 */
@Component
@RequiredArgsConstructor
public class LessonContentWriter {

    private final VideoProviderResolver videoProviderResolver;

    /**
     * Writes the content branch this lesson now uses, recording what a learner should be told.
     *
     * <p>Everything here goes through {@link CourseContentChanges}, which compares before it
     * assigns. That is what makes re-saving an unchanged lesson a genuine no-op: the document is
     * canonical JSON, so an instructor who opens a rich-content lesson and closes it again produces
     * a byte-identical string, no change is recorded, and nobody enrolled is told the course moved.
     *
     * <p>A change of type is itself recorded as a content change. Replacing a video with an article
     * is the largest edit a lesson can undergo, and a learner who completed it is entitled to see
     * that it is now a different thing.
     *
     * @return whether the video's length has to be looked up again
     */
    public boolean apply(Lesson lesson, LessonContent content, CourseContentChanges changes) {
        LessonContentType previousType = lesson.getContentType() == null
                ? LessonContentType.VIDEO
                : lesson.getContentType();

        changes.of(lesson).content(previousType, content.type(), lesson::setContentType);

        return content.isVideo()
                ? applyVideo(lesson, content, changes, previousType)
                : applyRichContent(lesson, content, changes);
    }

    private boolean applyVideo(Lesson lesson, LessonContent content, CourseContentChanges changes,
                               LessonContentType previousType) {
        VideoSource current = lesson.getVideo();
        String currentUrl = current == null ? null : current.getUrl();

        VideoSource next = content.isCarriedVideo()
                ? carriedForward(current)
                // Rewritten on every save, not only when the URL changed: a lesson stored before
                // providers existed picks up its provider, id and thumbnail the first time it is
                // edited, with no migration and no separate back-fill pass. A still that had to be
                // fetched is carried over rather than thrown away — see
                // ResolvedVideo#toVideoSource(VideoSource).
                : content.video().toVideoSource(current);

        boolean urlChanged = !java.util.Objects.equals(next.getUrl(), currentUrl);
        changes.of(lesson).content(current, next, lesson::setVideo);

        // Re-measured when the video is new to this lesson, which is not the same question as
        // whether the URL changed. A lesson switched to rich content and back carries the same URL
        // it always had, but its duration was zeroed on the way out — so "the URL is unchanged"
        // would leave it reporting a length of zero for a video that plays perfectly well.
        boolean nowPlaysAVideo = previousType != LessonContentType.VIDEO;
        if (urlChanged) {
            lesson.setDuration(0);
        }
        return urlChanged || nowPlaysAVideo;
    }

    /**
     * The video to store for a lesson whose video this save did not change.
     *
     * <p>Still re-derived when it can be — that is how a row written before the provider columns
     * existed gets them filled in by an ordinary save, which is behaviour worth keeping — but a URL
     * no adapter claims today keeps the source it already has instead of failing the save. That row
     * was accepted under the rules of its time and this request is not touching it; the read path
     * has always treated it that way, and this is the write path agreeing.
     *
     * @param current what the lesson holds now, never null for a carried video — "carried" means it
     *                matched something already stored
     */
    private VideoSource carriedForward(VideoSource current) {
        return videoProviderResolver.tryResolve(current.getUrl())
                .map(resolved -> resolved.toVideoSource(current))
                .orElse(current);
    }

    private boolean applyRichContent(Lesson lesson, LessonContent content, CourseContentChanges changes) {
        changes.of(lesson).content(lesson.getRichContent(), content.richContent(), lesson::setRichContent);

        // A read has no playback length, and a stale one would be counted into the course's total
        // duration and printed on the curriculum row as if the lesson were still a video.
        //
        // Assigned directly rather than through `changes`: this is derived from the type change
        // already recorded above, and recording it again would describe one edit twice.
        if (lesson.getDuration() == null || lesson.getDuration() != 0) {
            lesson.setDuration(0);
        }
        return false;
    }
}
