package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A lesson as the public outline shows it: its title and, when measured, its length. Nothing else
 * about the lesson — no video, body, summary or quiz — leaves the server through this type.
 *
 * @param preview always {@code false}: no lesson is playable without access. The field exists so a
 *                future, deliberately built public preview can say so without a contract change.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicOutlineLessonResponse(Long id, String title, Integer durationSeconds, boolean preview) {
}
