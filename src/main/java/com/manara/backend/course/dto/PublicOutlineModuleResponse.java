package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One group of a course's public outline. {@code moduleTitle} is {@code null} for a course whose
 * lessons are not grouped into modules, which then has exactly one such group.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicOutlineModuleResponse(String moduleTitle, List<PublicOutlineLessonResponse> lessons) {
}
