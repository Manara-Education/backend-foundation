package com.manara.backend.auth.dto;

import com.manara.backend.common.json.CanonicalEmailDeserializer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonDeserialize;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequest {

    @NotBlank(message = "{validation.fullName.required}")
    private String fullName;

    // Canonicalised as it is parsed, so every downstream layer — validation included — sees the
    // one form this application stores. See CanonicalEmailDeserializer.
    @JsonDeserialize(using = CanonicalEmailDeserializer.class)
    @Email(message = "{validation.email.invalid}")
    @NotBlank(message = "{validation.email.required}")
    private String email;

    @NotBlank(message = "{validation.password.required}")
    @Size(min = 6, message = "{validation.password.size}")
    private String password;

    private com.manara.backend.user.model.Role role;
}
