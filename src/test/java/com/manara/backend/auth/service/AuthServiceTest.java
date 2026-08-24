package com.manara.backend.auth.service;

import com.manara.backend.auth.dto.AuthResponse;
import com.manara.backend.auth.dto.ChangePasswordRequest;
import com.manara.backend.auth.dto.LoginRequest;
import com.manara.backend.auth.dto.ResetPasswordRequest;
import com.manara.backend.auth.mapper.AuthMapper;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.profile.mapper.ProfileMapper;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The forced-password-reset rules, from the two ends the client sees them: what sign-in and
 * session restore report, and what changing the password does to the flag.
 *
 * The mapper is real. A DTO field that never gets populated is precisely the failure this
 * feature dies of -- the column exists, the flag is set, and the client is never told -- so
 * these tests assert the value on the response the controller would return, not on the entity.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String EMAIL = "student@manara.com";
    private static final String CURRENT_PASSWORD = "password123";
    private static final String CURRENT_HASH = "$2a$10$currenthash";
    private static final String NEW_PASSWORD = "N3wPassword!";
    private static final String NEW_HASH = "$2a$10$newhash";

    @Mock
    private UserRepository userRepository;
    @Mock
    private InstructorRepository instructorRepository;
    @Mock
    private StudentRepository studentRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private OtpService otpService;
    @Mock
    private AuthenticationManager authenticationManager;
    @Mock
    private MessageService messageService;
    @Mock
    private SessionManager sessionManager;
    @Mock
    private ProfileMapper profileMapper;

    // Real, not stubbed: whether the flag survives the trip into the response DTO is half of
    // what is being tested. @Spy so @InjectMocks passes it to the constructor.
    @Spy
    private final AuthMapper authMapper = new AuthMapper();

    @InjectMocks
    private AuthService authService;

    @Captor
    private ArgumentCaptor<User> savedUser;

    private final HttpServletRequest httpRequest = new MockHttpServletRequest();
    private final HttpServletResponse httpResponse = new MockHttpServletResponse();

    private User user(boolean requiresPasswordReset) {
        return User.builder()
                .id(7L)
                .fullName("أحمد طارق")
                .email(EMAIL)
                .password(CURRENT_HASH)
                .role(Role.STUDENT)
                .emailVerified(true)
                .requiresPasswordReset(requiresPasswordReset)
                .build();
    }

    // ── Case 1 / 2: what sign-in reports ──────────────────────────────────────

    @Test
    void loginReportsNoPasswordResetForAnOrdinaryAccount() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(false)));
        given(authenticationManager.authenticate(any()))
                .willReturn(UsernamePasswordAuthenticationToken.authenticated(EMAIL, null, null));

        AuthResponse response = authService.login(
                LoginRequest.builder().email(EMAIL).password(CURRENT_PASSWORD).build(),
                httpRequest, httpResponse);

        assertThat(response.isRequiresPasswordReset()).isFalse();
        assertThat(response.getEmail()).isEqualTo(EMAIL);
    }

    @Test
    void loginSucceedsButReportsThePasswordResetForAFlaggedAccount() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(true)));
        given(authenticationManager.authenticate(any()))
                .willReturn(UsernamePasswordAuthenticationToken.authenticated(EMAIL, null, null));

        AuthResponse response = authService.login(
                LoginRequest.builder().email(EMAIL).password(CURRENT_PASSWORD).build(),
                httpRequest, httpResponse);

        // Authentication itself is not refused -- the account has the right password. What the
        // flag withholds is everything the client would do next.
        assertThat(response.isRequiresPasswordReset()).isTrue();
        verify(sessionManager).establish(any(Authentication.class), any(), any());
    }

    // ── Case 5: session restore ───────────────────────────────────────────────

    @Test
    void currentUserReadsTheFlagFromTheRowRatherThanFromTheSessionPrincipal() {
        // The principal the session hands over is a snapshot taken at sign-in: here it still
        // says "no reset required" while the row has since been flagged. /me must report the row.
        User stalePrincipal = user(false);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(true)));

        assertThat(authService.currentUser(stalePrincipal).isRequiresPasswordReset()).isTrue();
    }

    // ── Case 3: the change succeeds ───────────────────────────────────────────

    @Test
    void changingThePasswordStoresTheNewHashAndClearsTheResetRequirement() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(true)));
        given(passwordEncoder.matches(CURRENT_PASSWORD, CURRENT_HASH)).willReturn(true);
        given(passwordEncoder.matches(NEW_PASSWORD, CURRENT_HASH)).willReturn(false);
        given(passwordEncoder.encode(NEW_PASSWORD)).willReturn(NEW_HASH);

        authService.changePassword(user(true), changeRequest(NEW_PASSWORD));

        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPassword()).isEqualTo(NEW_HASH);
        assertThat(savedUser.getValue().getPassword()).isNotEqualTo(NEW_PASSWORD);
        assertThat(savedUser.getValue().isRequiresPasswordReset()).isFalse();
    }

    // ── Case 4: the change fails, the requirement stands ──────────────────────

    @Test
    void aWrongCurrentPasswordChangesNothingAndLeavesTheResetRequired() {
        User flagged = user(true);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(flagged));
        given(passwordEncoder.matches("wrong", CURRENT_HASH)).willReturn(false);

        assertThatThrownBy(() -> authService.changePassword(
                user(true),
                ChangePasswordRequest.builder()
                        .currentPassword("wrong")
                        .newPassword(NEW_PASSWORD)
                        .build()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("auth.password.currentInvalid");

        verify(userRepository, never()).save(any());
        assertThat(flagged.getPassword()).isEqualTo(CURRENT_HASH);
        assertThat(flagged.isRequiresPasswordReset()).isTrue();
    }

    @Test
    void reusingTheCurrentPasswordIsRejectedAndLeavesTheResetRequired() {
        User flagged = user(true);
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(flagged));
        given(passwordEncoder.matches(CURRENT_PASSWORD, CURRENT_HASH)).willReturn(true);

        assertThatThrownBy(() -> authService.changePassword(user(true), changeRequest(CURRENT_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("auth.password.sameAsCurrent");

        verify(userRepository, never()).save(any());
        assertThat(flagged.isRequiresPasswordReset()).isTrue();
    }

    // ── The emailed-code route out of the same state ──────────────────────────

    @Test
    void theOtpResetAlsoClearsTheRequirement() {
        // Otherwise a flagged user who goes the forgot-password way ends up with a new password
        // and still locked out of the application.
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(user(true)));
        given(passwordEncoder.encode(NEW_PASSWORD)).willReturn(NEW_HASH);

        authService.resetPassword(ResetPasswordRequest.builder()
                .email(EMAIL).code("123456").newPassword(NEW_PASSWORD).build());

        verify(otpService).verify(EMAIL, "123456", OtpType.PASSWORD_RESET);
        verify(userRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getPassword()).isEqualTo(NEW_HASH);
        assertThat(savedUser.getValue().isRequiresPasswordReset()).isFalse();
    }

    private ChangePasswordRequest changeRequest(String newPassword) {
        return ChangePasswordRequest.builder()
                .currentPassword(CURRENT_PASSWORD)
                .newPassword(newPassword)
                .build();
    }
}
