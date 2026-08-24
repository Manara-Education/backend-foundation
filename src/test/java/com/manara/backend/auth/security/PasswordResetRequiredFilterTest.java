package com.manara.backend.auth.security;

import com.manara.backend.auth.config.AuthSecurityConfig;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The server-side half of the forced-reset rule. The client's route guard sends the user to the
 * change-password screen; this filter is what makes that more than a suggestion, so the cases
 * that matter are the ones a hand-written HTTP request would try.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetRequiredFilterTest {

    private static final long USER_ID = 7L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private FilterChain chain;

    private PasswordResetRequiredFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PasswordResetRequiredFilter(
                userRepository, messageService, new ObjectMapper(), List.of(new AuthSecurityConfig()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PasswordResetRequiredFilterTest signedIn() {
        User principal = User.builder()
                .id(USER_ID).email("student@manara.com").role(Role.STUDENT).build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        return this;
    }

    private MockHttpServletResponse run(String method, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void refusesAProtectedEndpointWhileTheAccountOwesAPasswordChange() throws Exception {
        signedIn();
        given(userRepository.existsByIdAndRequiresPasswordResetTrue(USER_ID)).willReturn(true);
        given(messageService.get("auth.password.resetRequired"))
                .willReturn("You must change your password before continuing");

        MockHttpServletResponse response = run("GET", "/api/v1/courses");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("You must change your password");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void letsTheAccountReachTheEndpointsItNeedsToGetOutOfTheState() throws Exception {
        signedIn();
        given(userRepository.existsByIdAndRequiresPasswordResetTrue(USER_ID)).willReturn(true);

        // Change the password, read the current user, seed the CSRF token, or leave. Nothing
        // else -- and the allowlist is on method as well as path, so it cannot be widened by
        // sending a different verb to the same URL.
        assertThat(run("POST", "/api/v1/auth/change-password").getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(run("GET", "/api/v1/auth/me").getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(run("POST", "/api/v1/auth/logout").getStatus()).isEqualTo(HttpStatus.OK.value());

        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void doesNotWithholdEndpointsAnAnonymousCallerCouldReachAnyway() throws Exception {
        signedIn();
        given(userRepository.existsByIdAndRequiresPasswordResetTrue(USER_ID)).willReturn(true);

        // Public endpoints are exempt by definition -- refusing them to a signed-in account
        // withholds nothing, and would strand a flagged user who wants the emailed-code route
        // to a new password instead.
        assertThat(run("GET", "/api/v1/auth/csrf").getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(run("POST", "/api/v1/auth/forgot-password").getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(run("POST", "/api/v1/auth/reset-password").getStatus()).isEqualTo(HttpStatus.OK.value());

        verify(chain, times(3)).doFilter(any(), any());
    }

    @Test
    void staysOutOfTheWayOfAnAccountThatOwesNothing() throws Exception {
        signedIn();
        given(userRepository.existsByIdAndRequiresPasswordResetTrue(USER_ID)).willReturn(false);

        assertThat(run("GET", "/api/v1/courses").getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void doesNotQueryTheDatabaseForAnAnonymousRequest() throws Exception {
        assertThat(run("POST", "/api/v1/auth/login").getStatus()).isEqualTo(HttpStatus.OK.value());

        verify(chain).doFilter(any(), any());
        verify(userRepository, never()).existsByIdAndRequiresPasswordResetTrue(any());
    }
}
