package com.manara.backend.course.dto;

/**
 * A projection row for a module course's public outline: one per lesson, or one with a null lesson
 * for a module that has none.
 */
public record PublicModuleRow(Long moduleId, String moduleTitle, Long lessonId, String lessonTitle, Integer duration) {
}
