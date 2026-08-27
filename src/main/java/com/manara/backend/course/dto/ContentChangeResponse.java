package com.manara.backend.course.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The server's answer about one curriculum row, for the learner asking.
 *
 * <p>Carried on every module, lesson, quiz and exam of the enrolled course view so a client renders
 * a badge from a decision rather than making one. The alternative — shipping timestamps and letting
 * React compare them to an enrollment date — puts the rule in two places, and the two would
 * eventually disagree about the same lesson.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ContentChangeResponse {

    /** Always present. {@code UNCHANGED} for a viewer who is not enrolled. */
    private ContentChangeState state;

    /**
     * A sentence for the learner — "New lesson added", "Lesson content updated", "Lesson moved from
     * Module 1 to Module 2". Localised server-side from the request's {@code Accept-Language}, so
     * Arabic and English readers get the same decision in their own words.
     *
     * <p>Null when {@code state} is {@code UNCHANGED}, and null when the change predates the change
     * log — in which case {@code state} still answers correctly and the client falls back to a
     * plain "Updated".
     */
    private String summary;

    /** When the change happened, or when the item was created if it is {@code NEW}. */
    private LocalDateTime at;
}
