package com.manara.backend.profile.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailChangeResendRequest {

    @NotNull(message = "{validation.emailChange.requestRequired}")
    private UUID requestId;
}
