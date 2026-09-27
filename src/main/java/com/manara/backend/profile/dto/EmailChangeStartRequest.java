package com.manara.backend.profile.dto;

import tools.jackson.databind.annotation.JsonDeserialize;
import com.manara.backend.common.json.CanonicalEmailDeserializer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailChangeStartRequest {

    @NotBlank(message = "{validation.password.required}")
    private String currentPassword;

    @JsonDeserialize(using = CanonicalEmailDeserializer.class)
    @Email(message = "{validation.email.invalid}")
    @NotBlank(message = "{validation.email.required}")
    private String newEmail;
}
