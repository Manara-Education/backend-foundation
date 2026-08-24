package com.manara.backend.auth.mapper;

import com.manara.backend.auth.dto.RegisterRequest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
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

    @Test
    @DisplayName("the entity is built with the canonical address, not the one that was typed")
    void theStoredAddressIsCanonical() {
        // The mapper is the only place a User is constructed, and Lombok's builder writes fields
        // directly rather than through setEmail. So if canonicalisation is not done here, a
        // registration that reaches the service by any route other than a bound HTTP request puts
        // a raw address in the column — a row no lookup afterwards can find.
        User user = mapper.toUser(
                RegisterRequest.builder()
                        .fullName("أحمد طارق")
                        .email("  Student@Manara.com  ")
                        .build(),
                "$2a$10$hash",
                Role.STUDENT);

        assertThat(user.getEmail()).isEqualTo("student@manara.com");
    }

    @Test
    @DisplayName("two instances whose addresses differ only in case are the same account")
    void identityIgnoresCase() {
        // Matches how uk_users_email_lower sees them: the database would refuse to hold both, so
        // the application must not treat them as different accounts either.
        User lower = User.builder().email("student@manara.com").role(Role.STUDENT).build();
        User upper = User.builder().email("Student@Manara.com").role(Role.STUDENT).build();

        assertThat(lower).isEqualTo(upper);
        assertThat(lower).hasSameHashCodeAs(upper);
    }
}
