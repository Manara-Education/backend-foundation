package com.manara.backend.lesson.validation;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.lesson.content.RichContentSanitizer;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.video.service.VideoProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The one place that decides what a lesson payload has to carry.
 *
 * <p>What a lesson must contain now depends on what kind of lesson it is, and that conditional
 * lives here rather than in each caller. The standalone lesson endpoints and the course aggregate
 * save both come through it, so a rich-content lesson created through the course editor is held to
 * exactly the same standard as one created through the lesson API — including the sanitizer, which
 * is the property that actually matters. A second validation path that forgot to call it would be a
 * second way to get unsanitized content into the database.
 *
 * <p>Bean Validation cannot express this. {@code @NotBlank} on {@code videoUrl} was the previous
 * rule and it is the exact assumption this feature exists to remove: it made every lesson a video
 * lesson at the DTO boundary, before any code could know what kind of lesson was being saved. It is
 * gone, and this replaces it — which means the requirement is no weaker for video lessons, only
 * differently placed.
 */
@Component
@RequiredArgsConstructor
public class LessonContentValidator {

    private final VideoProviderResolver videoProviderResolver;
    private final RichContentSanitizer richContentSanitizer;

    /**
     * What kind of lesson a payload is asking for.
     *
     * <p>Absent means {@code VIDEO}. Every client written before this field existed omits it and
     * sends a video URL, and has to keep creating video lessons.
     */
    public LessonContentType typeOf(LessonRequest request) {
        return request.getContentType() == null ? LessonContentType.VIDEO : request.getContentType();
    }

    /**
     * Validates a lesson whose content is all new to this request — the standalone lesson
     * endpoints, where every save is an explicit edit of the one lesson it names.
     */
    public LessonContent validate(LessonRequest request) {
        return validate(request, false);
    }

    /**
     * Validates the content branch this lesson actually uses, and returns it ready to store.
     *
     * <p>The branch that is not used is not validated, which is the whole point: a rich-content
     * lesson is not asked for a video URL, and a video lesson is not asked for a document. Neither
     * is it read from the payload — see {@link LessonContent} — so a stale value left in the other
     * field by a round-tripping client cannot overwrite what is stored.
     *
     * <h2>Why the carried flag is a parameter rather than a lookup</h2>
     * Two callers ask this question and each already knows the answer more cheaply than this class
     * could find it: the course validator holds a {@code LessonVideoBaseline} read for the whole
     * payload, and the synchronizer holds the lesson entity itself. Taking the verdict keeps this
     * class free of both, and keeps it from issuing a query of its own per lesson.
     *
     * <h2>Rich content is deliberately never exempt</h2>
     * {@code videoAlreadyStored} applies to the video branch alone, and a rich-content lesson is
     * re-sanitized on every save whatever it says. Three reasons, and they are not symmetrical with
     * the video case. The sanitizer is the security boundary, and a boundary with an exemption is
     * not one. Its output is canonical, so re-running it over a stored document is idempotent and
     * changes nothing — which is what makes an unchanged save a genuine no-op. And there are no
     * legacy documents to be lenient about: the column is new, so everything in it was written by
     * this same sanitizer under these same rules. The leniency exists for rows accepted under
     * rules that have since changed, and rich content has no such history.
     *
     * @param videoAlreadyStored whether the submitted video is the one the course already holds for
     *                           this lesson — in which case it is not this save's to refuse
     * @throws BusinessException with the video or rich-content code describing what is wrong
     */
    public LessonContent validate(LessonRequest request, boolean videoAlreadyStored) {
        LessonContentType type = typeOf(request);
        if (type == LessonContentType.RICH_CONTENT) {
            return LessonContent.richContent(richContentSanitizer.sanitize(request.getRichContent()));
        }
        if (videoAlreadyStored) {
            return LessonContent.carriedVideo();
        }
        return LessonContent.video(
                videoProviderResolver.resolve(request.getVideoUrl(), request.getVideoProvider()));
    }
}
