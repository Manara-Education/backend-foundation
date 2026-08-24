package com.manara.backend.lesson.dto;

import com.manara.backend.quiz.dto.InstructorQuizResponse;
import com.manara.backend.video.model.VideoProvider;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Authoring view of a lesson, used only by the course editor endpoints.
 *
 * <p>The difference that matters is the quiz type: this carries {@link InstructorQuizResponse}
 * with the answer key, {@link LessonResponse} carries the learner view without it.
 *
 * <p>{@code videoUrl} is the address the instructor typed, returned unchanged so the editor
 * shows back what was entered rather than a rewritten form of it. The provider fields beside it
 * are derived from that URL by the server.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorLessonResponse {
    private Long id;
    private String title;
    private String summary;
    private String description;
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
    private InstructorQuizResponse quiz;
    private LocalDateTime createdAt;
}
