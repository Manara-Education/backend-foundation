package com.manara.backend.contact.dto;

import com.manara.backend.common.json.CanonicalEmailDeserializer;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The public contact form: {@code POST /api/v1/contact}.
 *
 * <p>{@code topic} is one of the fixed options the form offers, not free text — validated against
 * {@link ContactTopic#DISPLAY_VALUES} rather than trusted, since it is echoed into the notification
 * email's subject line. Anyone who could inject arbitrary text there would be writing part of an
 * email header on our behalf.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ContactRequest {

    @NotBlank(message = "{validation.contact.name.required}")
    @Size(max = 120, message = "{validation.contact.name.tooLong}")
    private String name;

    @JsonDeserialize(using = CanonicalEmailDeserializer.class)
    @Email(message = "{validation.email.invalid}")
    @NotBlank(message = "{validation.email.required}")
    private String email;

    @NotBlank(message = "{validation.contact.topic.required}")
    private String topic;

    @NotBlank(message = "{validation.contact.message.required}")
    @Size(max = 4000, message = "{validation.contact.message.tooLong}")
    private String message;
}
