package com.manara.backend.banner.dto;

import com.manara.backend.banner.model.BannerDisplayFrequency;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A banner as the management screen submits it, on both create and update.
 *
 * <p>Semantics are full replacement: whatever the payload carries becomes the banner, and an
 * omitted optional field clears the one that was there. The editor always sends the whole form, so
 * a partial payload would mean guessing which absent field was meant to be erased.
 *
 * <p>The cross-field rules — an end after its start, a link for a button that has a label, a window
 * once the banner leaves draft — live in {@code BannerValidator}, because each depends on another
 * field's value rather than on its own.
 */
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class BannerRequest {

    @NotBlank(message = "{validation.banner.internalName.required}")
    private String internalName;

    @NotBlank(message = "{validation.banner.title.required}")
    private String title;

    private String description;

    private String imageUrl;

    private String callToActionLabel;

    private String callToActionUrl;

    /** Omitted means the banner starts as soon as it is published. */
    private LocalDateTime startAt;

    /** Required once the banner leaves draft — a live banner has to say when it stops. */
    private LocalDateTime endAt;

    /** The zone the dates above were authored in. Defaults to {@code Asia/Riyadh} when omitted. */
    private String timezone;

    private Integer priority;

    private Boolean enabled;

    private Boolean dismissible;

    private BannerDisplayFrequency displayFrequency;

    /** {@code true} keeps the banner out of delivery entirely, whatever the rest of the payload says. */
    private Boolean draft;
}
