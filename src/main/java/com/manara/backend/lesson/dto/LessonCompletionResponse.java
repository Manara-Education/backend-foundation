package com.manara.backend.lesson.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What completing a lesson changed.
 *
 * <p>Marking a lesson done moves the course progress, may open the next module and may finish the
 * course. All of that is decided by the server, so it is reported back here rather than left for a
 * client to re-derive or discover on the next page load. It replaces the plain acknowledgement this
 * endpoint used to return.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonCompletionResponse {

    private Long lessonId;

    private Boolean completed;

    /** Percentage of the course's lessons now complete, 0-100. */
    private Integer courseProgress;

    /** The lesson to open next, or {@code null} when nothing is left to open. */
    private Long nextLessonId;

    /** True once the curriculum is finished and the final exam, if there is one, is passed. */
    private Boolean courseCompleted;
}
