package com.manara.backend.auth.mapper;

import com.manara.backend.auth.dto.RegisterRequest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ends of the mapping the forced-reset flag has to survive: nobody is enrolled into the
 * flow by accident on the way in, and anybody who is in it is told so on the way out.
 */
class AuthMapperTest {

    private final AuthMapper mapper = new AuthMapper();

    @Test
    void aNewlyRegisteredAccountIsNotRequiredToResetItsPassword() {
        // Case 6, at the application end: registration never asks for a reset, and the column
        // it is written to defaults to false, so existing rows behave the same after migration.
        User user = mapper.toUser(
                RegisterRequest.builder()
                        .fullName("أحمد طارق")
                        .email("student@manara.com")
                        .build(),
                "$2a$10$hash",
                Role.STUDENT);

        assertThat(user.isRequiresPasswordReset()).isFalse();
    }

    @Test
    void theResetRequirementReachesTheAuthenticationResponse() {
        User flagged = User.builder()
                .fullName("أحمد طارق")
                .email("student@manara.com")
                .role(Role.STUDENT)
                .requiresPasswordReset(true)
                .build();

        assertThat(mapper.toAuthResponse(flagged).isRequiresPasswordReset()).isTrue();
    }
}
