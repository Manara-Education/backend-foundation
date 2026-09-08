package com.manara.backend.session.security;

import com.manara.backend.auth.config.AuthSecurityConfig;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.session.manager.HttpSessionManager;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.assertj.core.api.InstanceOfAssertFactories;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The check that stops a session from outliving the account state it was opened under.
 *
 * <p>The cases below are the ones that decide whether the invariant holds at all: a session whose
 * stamp is missing, a session whose stamp has been left behind by a password change, and a session
 * that is still current but whose account has been demoted since. The first of those is also the
 * deploy path — every session written by the previous release is unstamped — so its consequences
 * are asserted in full rather than taken on trust.
 *
 * <p>The session manager is the real one, with only its context repository stubbed. Refusing a
 * session is not just a status code: the session has to be invalidated and both cookies expired, or
 * the client keeps presenting the same dead session and the browser keeps looking signed in. A
 * mocked manager would have proved the call was made and nothing about what it did.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SessionAuthenticationFreshnessFilterTest {

    private static final long USER_ID = 7L;
    private static final String REVOKED_MESSAGE = "Your session has ended. Please sign in again.";

    @Mock
    private UserRepository userRepository;
    @Mock
    private MessageService messageService;
    @Mock
    private SecurityContextRepository securityContextRepository;

    private SessionAuthenticationFreshnessFilter filter;

    /** What the chain saw, if it was reached at all. */
    private final AtomicReference<Authentication> downstream = new AtomicReference<>();
    private boolean chainWasCalled;

    @BeforeEach
    void setUp() {
        SessionManager sessionManager = new HttpSessionManager(securityContextRepository);
        filter = new SessionAuthenticationFreshnessFilter(
                userRepository,
                sessionManager,
                messageService,
                new ObjectMapper(),
                List.of(new AuthSecurityConfig()));

        given(messageService.get("auth.session.revoked")).willReturn(REVOKED_MESSAGE);

        downstream.set(null);
        chainWasCalled = false;
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── The deploy path, and the revocation it shares its mechanics with ──────

    @Test
    @DisplayName("a session carrying no stamp is refused, torn down and cleared of its cookies")
    void anUnstampedSessionIsRefusedAndEnded() throws Exception {
        signedIn(Role.STUDENT);
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 0L, false)));

        MockHttpSession session = new MockHttpSession();
        MockHttpServletResponse response = run("GET", "/api/v1/courses", session);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString())
                .contains("SESSION_REVOKED")
                .contains(REVOKED_MESSAGE);
        assertThat(chainWasCalled).as("the request must not reach the application").isFalse();

        assertThat(session.isInvalid())
                .as("left alive, the same dead session comes back on the next request")
                .isTrue();
        assertThat(expiredCookies(response))
                .as("and the browser would go on looking signed in")
                .contains("MANARA_SESSION", "XSRF-TOKEN");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    /**
     * Absent is refused rather than read as zero, and this is the distinction the deploy rests on.
     * A brand-new account really is at epoch 0, so treating "no stamp" as 0 would have honoured
     * every unstamped session belonging to an account that had never changed its password — which
     * is most of them.
     */
    @Test
    @DisplayName("an unstamped session is refused even when the account is itself at epoch 0")
    void anUnstampedSessionIsNotTreatedAsEpochZero() throws Exception {
        signedIn(Role.STUDENT);
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 0L, false)));

        assertThat(run("GET", "/api/v1/courses", new MockHttpSession()).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(chainWasCalled).isFalse();
    }

    @Test
    @DisplayName("a session stamped with a superseded epoch is refused")
    void aStaleStampIsRefused() throws Exception {
        signedIn(Role.STUDENT);
        // The account's password was changed somewhere else; the row moved on, this session did not.
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 4L, false)));

        MockHttpSession session = stampedSession(3L);
        MockHttpServletResponse response = run("GET", "/api/v1/courses", session);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(response.getContentAsString()).contains("SESSION_REVOKED");
        assertThat(chainWasCalled).isFalse();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    @DisplayName("a session whose account no longer exists is refused")
    void aSessionWithoutARowIsRefused() throws Exception {
        signedIn(Role.STUDENT);
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.empty());

        assertThat(run("GET", "/api/v1/courses", stampedSession(0L)).getStatus())
                .isEqualTo(HttpStatus.UNAUTHORIZED.value());
        assertThat(chainWasCalled).isFalse();
    }

    // ── The session that is still current ────────────────────────────────────

    @Test
    @DisplayName("a matching stamp passes the request on")
    void aCurrentSessionIsHonoured() throws Exception {
        signedIn(Role.STUDENT);
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 5L, false)));

        MockHttpSession session = stampedSession(5L);
        MockHttpServletResponse response = run("GET", "/api/v1/courses", session);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chainWasCalled).isTrue();
        assertThat(session.isInvalid()).isFalse();
    }

    /**
     * The role half of the finding. Roles move by hand, in SQL — nothing in the application ever
     * calls a setter for one — so a demotion reaches an open session only if something re-reads it.
     */
    @Test
    @DisplayName("a demoted account's open session is authorized as what the row says now")
    void authoritiesComeFromTheRowAndNotFromTheSnapshot() throws Exception {
        signedIn(Role.INSTRUCTOR);
        // Demoted out of band, between one request and the next, with no re-login in between.
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 5L, false)));

        run("GET", "/api/v1/instructor/courses/my-courses", stampedSession(5L));

        assertThat(chainWasCalled).isTrue();
        assertThat(downstream.get().getPrincipal())
                .asInstanceOf(InstanceOfAssertFactories.type(User.class))
                .extracting(User::getRole)
                .isEqualTo(Role.STUDENT);
        assertThat(downstream.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_STUDENT")
                .doesNotContain("ROLE_INSTRUCTOR");
    }

    @Test
    @DisplayName("the forced-reset flag on the refreshed principal is the row's, not the session's")
    void theResetFlagIsRefreshedTooSoTheLaterFilterCanReadIt() throws Exception {
        signedIn(Role.STUDENT);
        // Flagged by an operator after this session was opened.
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 5L, true)));

        run("GET", "/api/v1/courses", stampedSession(5L));

        assertThat(downstream.get().getPrincipal())
                .asInstanceOf(InstanceOfAssertFactories.type(User.class))
                .extracting(User::isRequiresPasswordReset)
                .as("PasswordResetRequiredFilter reads this instead of issuing a second query")
                .isEqualTo(true);
    }

    /**
     * The principal has to stay a {@code User}: roughly forty controller methods take it as
     * {@code @AuthenticationPrincipal User}, and handing them anything else turns every one of them
     * into a null argument rather than a compile error.
     */
    @Test
    @DisplayName("the refreshed principal is still a User, carrying the account's identity")
    void thePrincipalTypeIsUnchanged() throws Exception {
        signedIn(Role.STUDENT);
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 5L, false)));

        run("GET", "/api/v1/courses", stampedSession(5L));

        assertThat(downstream.get().getPrincipal())
                .asInstanceOf(InstanceOfAssertFactories.type(User.class))
                .satisfies(user -> {
                    assertThat(user.getId()).isEqualTo(USER_ID);
                    assertThat(user.getUsername()).isEqualTo("student@manara.com");
                    assertThat(user.getFullName()).isEqualTo("أحمد طارق");
                    assertThat(user.getAuthVersion()).isEqualTo(5L);
                });
    }

    // ── What must never be checked ───────────────────────────────────────────

    @Test
    @DisplayName("an anonymous request costs no database read and is never refused")
    void anonymousRequestsAreLeftAlone() throws Exception {
        MockHttpServletResponse response = run("POST", "/api/v1/auth/login", null);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chainWasCalled).isTrue();
        verify(userRepository, never()).findAuthStateById(any());
    }

    /**
     * The failure this would otherwise cause is a revocation with no way out: sign-in, the CSRF
     * seed and the emailed-code recovery flow are all public, and a user holding a dead cookie who
     * could not reach them would be locked out permanently rather than asked to sign in again.
     */
    @Test
    @DisplayName("public endpoints are exempt even for a session that would be refused elsewhere")
    void publicEndpointsAreNeverChecked() throws Exception {
        signedIn(Role.STUDENT);

        for (String[] call : List.of(
                new String[]{"GET", "/api/v1/auth/csrf"},
                new String[]{"POST", "/api/v1/auth/login"},
                new String[]{"POST", "/api/v1/auth/forgot-password"},
                new String[]{"POST", "/api/v1/auth/reset-password"})) {

            chainWasCalled = false;
            // An unstamped session -- the shape that is refused everywhere else.
            MockHttpServletResponse response = run(call[0], call[1], new MockHttpSession());

            assertThat(response.getStatus()).as("%s %s", call[0], call[1]).isEqualTo(HttpStatus.OK.value());
            assertThat(chainWasCalled).as("%s %s", call[0], call[1]).isTrue();
        }

        verify(userRepository, never()).findAuthStateById(any());
    }

    @Test
    @DisplayName("the stored session copy is left untouched, so no request re-serialises it")
    void theSessionIsNotRewrittenOnEveryRequest() throws Exception {
        signedIn(Role.INSTRUCTOR);
        given(userRepository.findAuthStateById(USER_ID)).willReturn(Optional.of(state(Role.STUDENT, 5L, false)));

        run("GET", "/api/v1/courses", stampedSession(5L));

        // Spring Security 6 does not persist the holder by itself. Saving here would push a
        // rewritten principal into the store on every single request, for no gain: the refreshed
        // copy is only ever needed for the request that built it.
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private void signedIn(Role role) {
        User principal = User.builder()
                .id(USER_ID)
                .fullName("أحمد طارق")
                .email("student@manara.com")
                .password("$2a$10$hash")
                .emailVerified(true)
                .role(role)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, principal.getAuthorities()));
    }

    private MockHttpSession stampedSession(long version) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionManager.AUTH_VERSION_ATTRIBUTE, version);
        return session;
    }

    private MockHttpServletResponse run(String method, String uri, MockHttpSession session) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri);
        if (session != null) {
            request.setSession(session);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            chainWasCalled = true;
            downstream.set(SecurityContextHolder.getContext().getAuthentication());
        });
        return response;
    }

    private static UserRepository.AuthState state(Role role, long authVersion, boolean requiresPasswordReset) {
        return new UserRepository.AuthState() {
            @Override
            public Long getId() {
                return USER_ID;
            }

            @Override
            public Role getRole() {
                return role;
            }

            @Override
            public boolean isRequiresPasswordReset() {
                return requiresPasswordReset;
            }

            @Override
            public long getAuthVersion() {
                return authVersion;
            }
        };
    }

    private static List<String> expiredCookies(MockHttpServletResponse response) {
        return Arrays.stream(response.getCookies())
                .filter(cookie -> cookie.getMaxAge() == 0)
                .map(Cookie::getName)
                .toList();
    }
}
