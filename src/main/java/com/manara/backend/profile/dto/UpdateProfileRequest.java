package com.manara.backend.profile.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;
import lombok.Getter;

@Getter
public class UpdateProfileRequest {

    /** Stripped as it is read, so both constraints judge the name that will actually be stored. */
    @NotBlank(message = "{validation.fullName.required}")
    @Size(max = 70, message = "{validation.fullName.size}")
    private final String fullName;

    @Builder
    @JsonCreator
    public UpdateProfileRequest(@JsonProperty("fullName") String fullName) {
        this.fullName = fullName == null ? null : fullName.strip();
    }
}
