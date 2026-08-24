package com.manara.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Changing the password of the signed-in account.
 *
 * Distinct from {@link ResetPasswordRequest}, which belongs to the anonymous forgot-password
 * flow and proves identity with an emailed OTP. Here the caller is already authenticated and
 * proves it knows the account by supplying the current password instead.
 *
 * There is no confirmation field: the reset flow does not have one either, and the two
 * password boxes are matched on the client before anything is sent.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "{validation.currentPassword.required}")
    private String currentPassword;

    @NotBlank(message = "{validation.newPassword.required}")
    @Size(min = 6, message = "{validation.password.size}")
    private String newPassword;
}
