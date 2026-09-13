package com.manara.backend.auth.dto;

import com.manara.backend.auth.password.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Changing the password of the signed-in account.
 *
 * Distinct from {@link ResetPasswordRequest}, which belongs to the anonymous forgot-password
 * flow and proves identity with an emailed OTP. Here the caller is already authenticated and
 * proves it knows the account by supplying the current password instead.
 *
 * There is no confirmation field: the reset flow does not have one either, and the two
 * password boxes are matched on the client before anything is sent.
 *
 * The policy's check against the account's own address and name is made in
 * {@code AuthService#changePassword}: both come from the session, not from this request.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChangePasswordRequest {

    @NotBlank(message = "{validation.currentPassword.required}")
    @ToString.Exclude
    private String currentPassword;

    @NotBlank(message = "{validation.newPassword.required}")
    @ValidPassword
    @ToString.Exclude
    private String newPassword;
}
