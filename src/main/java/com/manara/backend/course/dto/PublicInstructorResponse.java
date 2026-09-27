package com.manara.backend.course.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Who teaches a course, limited to what the instructor publishes: never an address or an id. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PublicInstructorResponse(String name, String avatarUrl, String headline) {
}
