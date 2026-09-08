package com.manara.backend.auth.security;

import com.manara.backend.auth.config.AuthSecurityConfig;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.session.manager.HttpSessionManager;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.session.security.SessionAuthenticationFreshnessFilter;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The server-side half of the forced-reset rule. The client's route guard sends the user to the
 * change-password screen; this filter is what makes that more than a suggestion, so the cases
 * that matter are the ones a hand-written HTTP request would try.
 *
 * <p>The filter no longer queries for the flag itself. It reads it off the principal, which
 * {@code SessionAuthenticationFreshnessFilter} has already replaced with one built from a row read
 * on this request — one account read per request rather than two, with the same guarantee. Most
 * cases below therefore set the flag on the principal directly, exactly as that filter would have.
 * The last one does not: it runs both filters in order, so that "the flag was set out of band and
 * the open session is locked on its very next request" is proved through the composition rather
 * than assumed of it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordResetRequiredFilterTest {

    private static final long USER_ID = 7L;

    @Mock
    private MessageService messageService;
    @Mock
    private FilterChain chain;

    private PasswordResetRequiredFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PasswordResetRequiredFilter(
                messageService, new ObjectMapper(), List.of(new AuthSecurityConfig()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PasswordResetRequiredFilterTest signedIn(boolean requiresPasswordReset) {
        User principal = User.builder()
                .id(USER_ID).email("student@manara.com").role(Role.STUDENT)
                .requiresPasswordReset(requiresPasswordReset).build();
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
        signedIn(true);
        given(messageService.get("auth.password.resetRequired"))
                .willReturn("You must change your password before continuing");

        MockHttpServletResponse response = run("GET", "/api/v1/courses");

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("You must change your password");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void letsTheAccountReachTheEndpointsItNeedsToGetOutOfTheState() throws Exception {
        signedIn(true);

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
        signedIn(true);

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
        signedIn(false);

        assertThat(run("GET", "/api/v1/courses").getStatus()).isEqualTo(HttpStatus.OK.value());
        verify(chain).doFilter(any(), any());
    }

    @Test
    void ignoresAnAnonymousRequest() throws Exception {
        assertThat(run("POST", "/api/v1/auth/login").getStatus()).isEqualTo(HttpStatus.OK.value());

        verify(chain).doFilter(any(), any());
    }

    /**
     * The two filters in the order the chain runs them, against a session that was opened before an
     * operator flagged the account.
     *
     * <p>This is the case the old design paid a second query for, and it is the one that would fail
     * silently if the freshness filter stopped refreshing the principal or were moved after this
     * one: the session's own snapshot says the account owes nothing, and nothing but the row read
     * upstream contradicts it.
     */
    @Test
    @DisplayName("a flag set out of band locks the already-open session on its very next request")
    void aFlagSetAfterSignInStillLocksTheOpenSession() throws Exception {
        UserRepository userRepository = mock(UserRepository.class);
        SecurityContextRepository contextRepository = mock(SecurityContextRepository.class);
        SessionAuthenticationFreshnessFilter freshnessFilter = new SessionAuthenticationFreshnessFilter(
                userRepository,
                new HttpSessionManager(contextRepository),
                messageService,
                new ObjectMapper(),
                List.of(new AuthSecurityConfig()));

        // Signed in before the flag was set: the session's own copy says the account owes nothing.
        signedIn(false);
        given(messageService.get("auth.password.resetRequired"))
                .willReturn("You must change your password before continuing");
        // The row says otherwise, and the session is otherwise perfectly current.
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(new UserRepository.AuthState() {
            @Override
            public Long getId() {
                return USER_ID;
            }

            @Override
            public Role getRole() {
                return Role.STUDENT;
            }

            @Override
            public boolean isRequiresPasswordReset() {
                return true;
            }

            @Override
            public long getAuthVersion() {
                return 3L;
            }
        }));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        request.setServletPath("/api/v1/courses");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionManager.AUTH_VERSION_ATTRIBUTE, 3L);
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();

        freshnessFilter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, chain));

        assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(response.getContentAsString()).contains("You must change your password");
        verify(chain, never()).doFilter(any(), any());
        assertThat(session.isInvalid())
                .as("the account is locked out of the API, not signed out -- it still has to reach "
                        + "the change-password endpoint")
                .isFalse();
    }
}
