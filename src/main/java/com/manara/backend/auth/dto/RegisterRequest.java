package com.manara.backend.auth.dto;

import com.manara.backend.common.json.CanonicalEmailDeserializer;
import com.manara.backend.common.json.StrictBooleanDeserializer;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    /**
     * Whether the visitor accepted the Terms and Conditions. Must be an explicit JSON {@code true}.
     *
     * <p>Boxed {@code Boolean}, never {@code boolean}, and both annotations together — neither is
     * redundant. A primitive would bind a missing field to {@code false} with no way to tell that
     * apart from a deliberate refusal, so the "you did not answer" case would disappear. And
     * {@code @AssertTrue} passes on {@code null} by specification, so on its own it would let an
     * omitted field through. {@code @NotNull} catches the silence, {@code @AssertTrue} catches the
     * "no".
     *
     * <p>{@link StrictBooleanDeserializer} is the third part: it refuses {@code "true"},
     * {@code 1} and every other shape Jackson would ordinarily coerce. Consent has to be something
     * the sender expressed, not something the parser was generous enough to infer.
     */
    @NotNull(message = "{validation.terms.accepted.required}")
    @AssertTrue(message = "{validation.terms.accepted.mustAccept}")
    @JsonDeserialize(using = StrictBooleanDeserializer.class)
    private Boolean termsAccepted;

    /**
     * The version of the Terms and Conditions that was accepted, as
     * {@code GET /api/v1/terms/current} named it.
     *
     * <p>Sent back rather than assumed, so consent is recorded against the exact text that was on
     * screen. The server checks it against the version in force and refuses anything else with
     * {@code 409 TERMS_VERSION_OUTDATED} — a form left open across a publication must not be able to
     * accept the old terms silently.
     */
    @NotBlank(message = "{validation.terms.version.required}")
    private String termsVersion;
}
