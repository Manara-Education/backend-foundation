package com.manara.backend.lesson.dto;

import com.manara.backend.course.dto.ContentChangeResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import com.manara.backend.lesson.model.LessonContentType;
import com.manara.backend.video.model.VideoProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Learner-facing lesson. The attached quiz is the {@link LearnerQuizResponse} view, so course and
 * lesson browsing can never hand out an answer key.
 *
 * <p>When {@code locked} is true the viewer has not earned the lesson's content, and the fields
 * that carry it — every {@code video*} field, {@code description} and {@code quiz} — are absent.
 * What is left is the title, length and position, which is what a locked row in the curriculum
 * shows.
 *
 * <p>The video is described by four flat fields rather than a nested object so that clients
 * written against the previous contract, which knew only {@code videoUrl}, keep working without
 * a change: the provider fields are additions beside it, not a replacement for it.
 *
 * @see InstructorLessonResponse for the authoring view
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonResponse {
    private Long id;
    private String title;
    private String summary;
    private String description;
    /**
     * Which kind of lesson this is. Always present, so a client dispatches on a stated fact rather
     * than guessing from whether {@code videoUrl} came back null.
     */
    private LessonContentType contentType;

    /**
     * The authored document, canonical JSON, for a {@code RICH_CONTENT} lesson.
     *
     * <p>Null for a video lesson, and null for a locked one — it is lesson content, withheld by the
     * same rule that withholds the video.
     */
    private String richContent;
    private String videoUrl;

    /**
     * Which platform hosts {@code videoUrl} — {@code YOUTUBE}, {@code VIMEO}, and whatever is added
     * next. Null only when the stored URL is one no adapter recognises, which a client should treat
     * as "no player available" rather than as an error.
     */
    private VideoProvider videoProvider;

    /** The provider's own id for the video, for clients that need to address it directly. */
    private String externalVideoId;

    /**
     * What to point an iframe at. Carries no player options: those differ per surface and the
     * {@code origin} parameter can only be supplied by the browser, so clients append their own.
     */
    private String videoEmbedUrl;

    /** Still image for the video, when the provider offers one. */
    private String videoThumbnailUrl;
    private String duration;
    private Integer orderIndex;
    private Long courseId;
    private Long moduleId;
    private Boolean isCompleted;

    /** True when the viewer may see this lesson listed but not open it. */
    private Boolean locked;

    private LearnerQuizResponse quiz;

    /**
     * Whether this lesson is new or updated <em>to the learner reading it</em>, and what to say
     * about it.
     *
     * <p>Present on the enrolled course-details tree. Absent everywhere the question has no answer:
     * for a visitor browsing the catalogue, and on the endpoints that serve a single lesson rather
     * than a curriculum.
     *
     * <p>Deliberately a decision rather than a timestamp. Shipping {@code contentUpdatedAt} and
     * letting the client compare it to an enrollment date would put the rule in two places, and the
     * two would drift.
     */
    private ContentChangeResponse change;

    private LocalDateTime createdAt;
}
