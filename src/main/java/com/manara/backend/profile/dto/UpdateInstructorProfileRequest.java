package com.manara.backend.profile.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;

@Getter
public class UpdateInstructorProfileRequest {

    /** Stripped as it is read; blank clears it. Plain text — the public page never renders markup. */
    @Size(max = 120, message = "{validation.instructor.headline.size}")
    private final String headline;

    @Builder
    @JsonCreator
    public UpdateInstructorProfileRequest(@JsonProperty("headline") String headline) {
        String stripped = headline == null ? null : headline.strip();
        this.headline = stripped == null || stripped.isEmpty() ? null : stripped;
    }
}
