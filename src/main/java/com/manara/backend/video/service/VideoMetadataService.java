package com.manara.backend.video.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.video.model.ResolvedVideo;
import com.manara.backend.video.model.VideoMetadata;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.provider.VideoProviderAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fills in what only the provider knows — how long the video runs, and what it looks like — after
 * the lesson has already been saved.
 *
 * <p>This is the provider-independent replacement for the prototype's {@code YoutubeDurationService}.
 * The shape of the job is unchanged: it runs off the request thread, it updates the lesson's
 * duration, and it recomputes the course total from the lessons. What changed is who is asked. The
 * lookup now goes through the adapter for the lesson's own provider, so a Vimeo lesson gets a
 * duration by exactly the same route a YouTube lesson does and every consumer of
 * {@code course.duration} — progress, curriculum, course cards — carries on unaware that more than
 * one platform exists.
 *
 * <p>A failed lookup is not an error path. The lesson keeps the duration it had (zero for a new
 * one), the course total stays consistent with its lessons, and the next save tries again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoMetadataService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final VideoProviderResolver videoProviderResolver;

    /**
     * Refreshes one lesson's video metadata in the background.
     *
     * <p>Callers pass the lesson id rather than the entity because this runs in its own thread and
     * its own transaction: the entity that triggered it belongs to a persistence context that is
     * already closed by the time this executes.
     */
    @Async
    @Transactional
    public void refreshAsync(Long lessonId, VideoSource source) {
        if (lessonId == null || source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            return;
        }

        videoProviderResolver.describe(source)
                .flatMap(resolved -> videoProviderResolver.adapterFor(resolved.provider())
                        .map(adapter -> fetch(adapter, resolved)))
                .filter(metadata -> !metadata.isEmpty())
                .ifPresent(metadata -> apply(lessonId, metadata));
    }

    private VideoMetadata fetch(VideoProviderAdapter adapter, ResolvedVideo resolved) {
        try {
            return adapter.fetchMetadata(resolved.reference(), resolved.url());
        } catch (RuntimeException e) {
            // The interface asks adapters not to throw; this is the belt to that braces, so a
            // third-party client wrapping an error in something unexpected cannot kill the worker.
            log.warn("Video metadata lookup failed for provider={} videoId={}: {}",
                    resolved.provider(), resolved.externalId(), e.getMessage());
            return VideoMetadata.empty();
        }
    }

    /**
     * Writes back only what the provider actually answered.
     *
     * <p>The lesson is re-read here: it may have been edited, or deleted, between the save that
     * scheduled this and now. A lesson that has since been pointed at a different video would
     * otherwise be given the previous video's running time.
     *
     * <p>And it may no longer be a video lesson at all. An instructor who saves a video lesson and
     * immediately switches it to rich content leaves this lookup in flight against a lesson that
     * now has no playback — and a duration written onto it would be counted into the course's total
     * and printed on a curriculum row for an article. The type is therefore re-checked here rather
     * than at the call site, because the call site's answer was true when it was asked and this is
     * the only place that knows what is true when the write happens.
     */
    private void apply(Long lessonId, VideoMetadata metadata) {
        lessonRepository.findById(lessonId).ifPresent(lesson -> {
            if (lesson.getContentType() == LessonContentType.RICH_CONTENT) {
                log.debug("Lesson {} is no longer a video lesson; discarding its metadata lookup",
                        lessonId);
                return;
            }

            boolean changed = false;

            if (metadata.hasDuration()) {
                lesson.setDuration(metadata.durationSeconds());
                changed = true;
            }
            // A thumbnail the provider had to be asked for (Vimeo) is worth storing; one that is
            // derivable from the id (YouTube) is already there and this is a no-op.
            if (metadata.hasThumbnail() && lesson.getVideo() != null) {
                lesson.getVideo().setThumbnailUrl(metadata.thumbnailUrl());
                changed = true;
            }
            if (!changed) return;

            lessonRepository.saveAndFlush(lesson);

            if (metadata.hasDuration()) {
                Course course = lesson.getCourse();
                course.setDuration(lessonRepository.sumDurationByCourseId(course.getId()));
                courseRepository.save(course);
            }
        });
    }
}
