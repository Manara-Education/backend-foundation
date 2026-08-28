package com.manara.backend.lesson.validation;

import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.video.model.ResolvedVideo;

/**
 * A lesson's content, already validated, with exactly one of the branches filled.
 *
 * <p>Resolved once per lesson per request and passed on, rather than each layer asking the question
 * again. That matters most on the write path: the video is resolved before anything is assigned, so
 * a save carrying an unplayable URL is refused with the lesson untouched instead of half-applied —
 * and the same is now true of a document carrying a {@code javascript:} link.
 *
 * <h2>Three states, not two</h2>
 * A video lesson can arrive two ways, and the difference decides how strictly it is treated:
 *
 * <ul>
 *   <li>{@link #video(ResolvedVideo)} — the instructor chose this video. It has been resolved
 *       strictly and is held to today's rules in full.
 *   <li>{@link #carriedVideo()} — the payload is echoing the video the course already stores. It
 *       was accepted under the rules of its time and this request is not touching it, so it is
 *       re-derived leniently at write time rather than re-resolved. Without this, one legacy row
 *       froze the whole course around it.
 * </ul>
 *
 * @param type        which branch is filled
 * @param video       the strictly resolved video, or {@code null} for rich content and for a
 *                    carried video
 * @param richContent canonical sanitized JSON, or {@code null} for a video lesson
 */
public record LessonContent(LessonContentType type, ResolvedVideo video, String richContent) {

    public static LessonContent video(ResolvedVideo resolved) {
        return new LessonContent(LessonContentType.VIDEO, resolved, null);
    }

    /**
     * A video lesson whose video the payload did not change.
     *
     * <p>Carries no {@link ResolvedVideo} precisely because none was resolved — the point is that
     * this request never asked the resolver a question it could refuse to answer. What to store is
     * worked out from the lesson itself; see {@code LessonContentWriter}.
     */
    public static LessonContent carriedVideo() {
        return new LessonContent(LessonContentType.VIDEO, null, null);
    }

    public static LessonContent richContent(String sanitizedJson) {
        return new LessonContent(LessonContentType.RICH_CONTENT, null, sanitizedJson);
    }

    public boolean isVideo() {
        return type == LessonContentType.VIDEO;
    }

    /** Whether this is a video lesson whose video the payload only carried back. */
    public boolean isCarriedVideo() {
        return isVideo() && video == null;
    }
}
