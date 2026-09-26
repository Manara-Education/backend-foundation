package com.manara.backend.course.dto;

/** A projection row for the public outline: selected column by column, so no lesson content is loaded. */
public record PublicOutlineRow(Long lessonId, String title, Integer duration, Long moduleId) {
}
