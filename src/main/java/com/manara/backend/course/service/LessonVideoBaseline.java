package com.manara.backend.course.service;

import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The videos a course's lessons already hold, so validation can tell a video the instructor chose
 * from one the payload is merely carrying back.
 *
 * <p>The course editor saves the whole aggregate: a save that changes nothing but the price still
 * posts every lesson of the course, each with the video it already had. Validating all of them as
 * though they had just been typed is what made a single legacy row — a NAFS import, a migration, a
 * URL shape Manara has since stopped recognising — freeze the entire course, including its title
 * and its price. The read path never had that problem; it is documented as deliberately lenient
 * about exactly these rows, and this is the write path being made consistent with it.
 *
 * <p>What this is not is a relaxation of the rules. A lesson whose video the payload actually
 * changes, and every newly added lesson, is held to the current standard in full. The boundary is
 * "did this save touch it", not "is it published" — see {@link CourseValidator#resolveAndValidate}.
 *
 * <h2>Lazy on purpose</h2>
 * Most saves never ask a question of it: a metadata-only update carries no lessons, and a create
 * has no history to consult. The query therefore runs on first use and not before, and never at all
 * for {@link #none()}.
 */
public final class LessonVideoBaseline {

    private static final LessonVideoBaseline NONE = new LessonVideoBaseline(Map::of);

    private final Supplier<Map<Long, VideoSource>> source;
    private Map<Long, VideoSource> stored;

    private LessonVideoBaseline(Supplier<Map<Long, VideoSource>> source) {
        this.source = source;
    }

    /** For a course being created: nothing is stored yet, so nothing can be unchanged. */
    public static LessonVideoBaseline none() {
        return NONE;
    }

    /** The videos of the given lessons, read only if something asks. */
    public static LessonVideoBaseline of(Supplier<List<Lesson>> lessons) {
        return new LessonVideoBaseline(() -> lessons.get().stream()
                .filter(lesson -> lesson.getId() != null && lesson.getVideo() != null)
                .collect(Collectors.toMap(Lesson::getId, Lesson::getVideo, (first, duplicate) -> first)));
    }

    /**
     * Whether the course already stores exactly this video for this lesson.
     *
     * <p>False for a lesson with no id — a lesson being created has no stored video to be unchanged
     * from — and false for an id this course does not own, which the synchronizer refuses separately
     * and by name.
     */
    public boolean holds(Long lessonId, String submittedUrl, VideoProvider declaredProvider) {
        return storedFor(lessonId)
                .map(video -> video.matches(submittedUrl, declaredProvider))
                .orElse(false);
    }

    private Optional<VideoSource> storedFor(Long lessonId) {
        if (lessonId == null) {
            return Optional.empty();
        }
        if (stored == null) {
            stored = source.get();
        }
        return Optional.ofNullable(stored.get(lessonId));
    }
}
