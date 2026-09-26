package com.manara.backend.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailChangeVerifyRequest {

    @NotNull(message = "{validation.emailChange.requestRequired}")
    private UUID requestId;

    @NotBlank(message = "{validation.otp.required}")
    @Pattern(regexp = "\\d{6}", message = "{validation.otp.size}")
    private String code;
}
