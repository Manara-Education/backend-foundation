package com.manara.backend.profile.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The instructor's public-facing profile fields. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record InstructorProfileResponse(String headline) {
}
