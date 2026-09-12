package com.manara.backend.auth.dto;

import com.manara.backend.auth.password.PasswordNotPersonal;
import com.manara.backend.auth.password.PasswordOwner;
import com.manara.backend.auth.password.ValidPassword;
import com.manara.backend.common.json.CanonicalEmailDeserializer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import tools.jackson.databind.annotation.JsonDeserialize;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@PasswordNotPersonal(passwordField = "newPassword")
public class ResetPasswordRequest implements PasswordOwner {

    // Canonicalised as it is parsed, so every downstream layer — validation included — sees the
    // one form this application stores. See CanonicalEmailDeserializer.
    @JsonDeserialize(using = CanonicalEmailDeserializer.class)
    @Email(message = "{validation.email.invalid}")
    @NotBlank(message = "{validation.email.required}")
    private String email;

    @NotBlank(message = "{validation.otp.required}")
    @Size(min = 6, max = 6, message = "{validation.otp.size}")
    private String code;

    @NotBlank(message = "{validation.newPassword.required}")
    @ValidPassword
    @ToString.Exclude
    private String newPassword;

    @Override
    public String proposedPassword() {
        return newPassword;
    }

    @Override
    public String accountEmail() {
        return email;
    }

    /** Not part of this request; the address is the identity the caller has just proved. */
    @Override
    public String accountFullName() {
        return null;
    }
}
