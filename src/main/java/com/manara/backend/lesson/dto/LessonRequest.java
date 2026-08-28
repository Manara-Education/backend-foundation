package com.manara.backend.lesson.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.video.model.VideoProvider;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A lesson, either on its own (instructor lesson endpoints) or nested inside a course payload.
 *
 * <p>The Bean Validation annotations apply to the standalone endpoints, which pass this through
 * {@code @Valid}. Inside a course payload the aggregate validator takes over: {@code orderIndex} is
 * ignored there because position in the array is the authoritative order, and {@code id} decides
 * whether an existing lesson is updated or a new one created.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonRequest {

    /** Set to update an existing lesson of the course; omit to create a new one. */
    private Long id;

    @NotBlank(message = "{validation.lesson.title.required}")
    private String title;

    private String summary;

    private String description;

    /**
     * The lesson's video, on any platform Manara supports. The provider is worked out from this by
     * the server, so a client never has to know how to tell YouTube from Vimeo.
     *
     * <p>Named for what it is rather than for one platform: the prototype's field carried the same
     * value under the same name, so this is not a contract change, only an honest one.
     */
    @NotBlank(message = "{validation.lesson.videoUrl.required}")
    private String videoUrl;

    /**
     * Optional, and never taken at face value.
     *
     * <p>A client that already knows which platform it is sending may say so, and the server checks
     * that claim against the URL and rejects the pair if they disagree. What it will not do is
     * believe it: a payload claiming {@code YOUTUBE} for a {@code vimeo.com} link is refused rather
     * than stored, so the provider column can never end up describing a different video from the
     * one the URL points at. Omitting this — which every current client does — is the normal case.
     */
    private VideoProvider videoProvider;

    /**
     * Where the lesson should sit among its siblings, counting from zero. Optional.
     *
     * <p>Omitted means "at the end", which is what adding a lesson normally means and what no
     * client could previously say. Given, it is an insertion point: the lesson goes there and the
     * siblings from that position down move one place along. Out of range is refused by name.
     *
     * <p>It was mandatory, and written straight into a {@code UNIQUE (course_id, module_id,
     * order_index)} constraint, so a client had to compute a value it had no safe way to compute —
     * and the obvious choice, {@code 0}, was a duplicate-key {@code 409}. Inside a course payload
     * this field is ignored entirely: position in the array is the order there.
     */
    @JsonAlias("order")
    private Integer orderIndex;

    /**
     * Module this lesson belongs to. Required by the standalone endpoints when the course uses
     * modules; inside a course payload the nesting already says it, and this field is ignored.
     */
    private Long moduleId;

    /** Optional — {@code null} removes the lesson's quiz. */
    private QuizRequest quiz;
}
