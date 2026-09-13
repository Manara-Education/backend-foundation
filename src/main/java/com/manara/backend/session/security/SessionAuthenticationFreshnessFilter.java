package com.manara.backend.session.security;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Checks, once per authenticated request, that the session is still standing on the account it was
 * opened against — and hands the rest of the chain a principal read from the row rather than the
 * one the session remembers.
 *
 * <p>A session is a snapshot, and until this filter existed nothing ever re-examined it. The
 * password it was opened with could be replaced and the session carried on; the role it was opened
 * under could be taken away and authorization kept granting the old one, because the authorities
 * come from a {@code Role} field serialised at sign-in. "Change your password, you may have been
 * compromised" therefore did nothing at all to whoever was holding the compromised session.
 *
 * <p>Two things are put right here, both from the same single row read:
 *
 * <ul>
 *   <li><b>Revocation.</b> The session records the account's authentication epoch at the moment it
 *       was established. Changing or resetting a password increments that epoch in the same
 *       transaction as the new hash. A session whose stamp is missing or no longer matches the row
 *       is torn down and refused with {@link ErrorCode#SESSION_REVOKED}.</li>
 *   <li><b>Authority freshness.</b> On a session that does match, the authentication in the context
 *       is replaced with one whose authorities are derived from the role the row holds now. This
 *       runs <em>before</em> {@code AuthorizationFilter}, which is the whole reason it is a separate
 *       filter from {@code PasswordResetRequiredFilter} rather than more code inside it: refreshed
 *       authorities are worth nothing if authorization has already been decided.</li>
 * </ul>
 *
 * <p>A missing stamp is refused rather than assumed to be epoch zero. That is what carries the
 * previous release's sessions across this deploy: they were serialised by a build that did not know
 * about the attribute, so every one of them is unstamped, and every one of them is asked to sign in
 * again on its next request. It costs each signed-in user one re-login and it means no command has
 * to be run against the session store — which matters, because that store also holds the rate-limit
 * counters, and clearing those would be handing an attacker a fresh allowance.
 *
 * <p>The context is deliberately not written back. Spring Security 6 does not persist the
 * {@code SecurityContextHolder} automatically, and nothing here calls
 * {@code SecurityContextRepository#saveContext}, so the copy stored in the session is left exactly
 * as it was: the refreshed principal lives for this request only, and no request re-serialises the
 * session as a side effect of being checked.
 */
@Slf4j
@Component
public class SessionAuthenticationFreshnessFilter extends OncePerRequestFilter {

    private final UserRepository userRepository;
    private final SessionManager sessionManager;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    /**
     * Every endpoint the application serves without a session, derived from the same contributions
     * the filter chain builds its {@code permitAll} rules from.
     *
     * <p>Exempt by definition, and exempt for a reason that is easy to get wrong: an anonymous
     * caller may already reach all of them, so refusing one to a holder of a dead session withholds
     * nothing — while checking them would strand exactly the user who most needs them. Sign-in, the
     * CSRF seed and the emailed-code recovery flow are all on this list, and a revoked session that
     * could not reach them would be a revocation the user has no way out of.
     *
     * <p>Derived rather than listed, so an endpoint made public later cannot fall into the gap
     * between two allowlists.
     */
    private final RequestMatcher publicEndpoints;

    public SessionAuthenticationFreshnessFilter(
            UserRepository userRepository,
            SessionManager sessionManager,
            MessageService messageService,
            ObjectMapper objectMapper,
            List<PublicEndpointContribution> publicEndpointContributions) {
        this.userRepository = userRepository;
        this.sessionManager = sessionManager;
        this.messageService = messageService;
        this.objectMapper = objectMapper;
        this.publicEndpoints = new OrRequestMatcher(publicEndpointContributions.stream()
                .flatMap(contribution -> contribution.endpoints().stream())
                .map(PublicEndpoint::toMatcher)
                .toList());
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User principal = principalOf(authentication);

        if (principal == null || publicEndpoints.matches(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        Optional<UserRepository.AuthState> state = userRepository.findAuthStateById(principal.getId());

        if (state.isEmpty() || !isCurrent(request, state.get())) {
            // Covers the account that no longer exists as well as the stale epoch: a session
            // outliving its row is no more entitled to be honoured than one outliving its password.
            refuse(request, response);
            return;
        }

        SecurityContextHolder.getContext().setAuthentication(refreshed(principal, state.get()));
        filterChain.doFilter(request, response);
    }

    /**
     * Whether this session belongs to the account's current authentication epoch.
     *
     * <p>Absent means no. A session with no stamp was written by a build that did not stamp, so
     * nothing is known about the credentials it was opened under, and "nothing is known" is not a
     * reason to honour it. Every session that predates this change is in exactly that position and
     * is signed out once, on its next request.
     *
     * <p>A request carrying no session at all is a different case, and the epoch is not what
     * settles it. Authentication here is the session cookie — the security context is loaded from
     * the session store and from nowhere else — so a request with no session cannot arrive
     * authenticated, and there is no session for a password change to have revoked. What such a
     * request must still not do is act on a stale role or a deleted account, and it does not: the
     * account is looked up and the principal is rebuilt from the row either way, a few lines above.
     * Only this comparison is skipped, and only because there is nothing to compare.
     */
    private boolean isCurrent(HttpServletRequest request, UserRepository.AuthState state) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return true;
        }

        Object stamped = session.getAttribute(SessionManager.AUTH_VERSION_ATTRIBUTE);
        return stamped instanceof Long version && version == state.getAuthVersion();
    }

    /**
     * The principal the rest of the request sees: this account, carrying the role and the
     * forced-reset flag the row holds right now, with authorities derived from that role.
     *
     * <p>A new instance rather than a mutated one. The object being copied from is the session's own
     * deserialised principal, and writing to it would be writing into the session's object graph —
     * a place whose contents are supposed to be a fixed record of what was true at sign-in.
     *
     * <p>The identity fields are carried across from that record, and only the three fields a
     * request is authorized on come from the database. That is not a gap: a name or an address
     * changing does not decide whether a request is allowed, and any change to the credentials
     * themselves ends the session outright a few lines above rather than editing it.
     */
    private Authentication refreshed(User principal, UserRepository.AuthState state) {
        User current = User.builder()
                .id(principal.getId())
                .fullName(principal.getFullName())
                .email(principal.getEmail())
                .password(principal.getPassword())
                .emailVerified(principal.isEmailVerified())
                .requiresPasswordReset(state.isRequiresPasswordReset())
                .role(state.getRole())
                .createdAt(principal.getCreatedAt())
                .updatedAt(principal.getUpdatedAt())
                .authVersion(state.getAuthVersion())
                .build();

        return UsernamePasswordAuthenticationToken.authenticated(
                current, null, current.getAuthorities());
    }

    /**
     * Ends the session and says why.
     *
     * <p>Torn down, not merely refused: leaving the cookie alive would have the client re-present
     * the same dead session on every subsequent request, and the browser would keep looking signed
     * in while nothing worked. {@code terminate} invalidates the session — which is also what
     * removes it from the session store — clears the context and expires both cookies.
     *
     * <p>The code travels in the standard error envelope so the client can tell this apart from an
     * ordinary "not signed in" and say so, instead of dropping the user on a login screen with no
     * explanation.
     */
    private void refuse(HttpServletRequest request, HttpServletResponse response) throws IOException {
        log.info("Refusing a session that no longer matches its account's authentication state");

        sessionManager.terminate(request, response);

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(messageService.get("auth.session.revoked"), ErrorCode.SESSION_REVOKED));
    }

    /**
     * The signed-in user, or {@code null} when this request is anonymous or its principal is not one
     * of ours — every unauthenticated and every pre-authentication call, which are somebody else's
     * concern and must not cost a database read.
     */
    private User principalOf(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof User user && user.getId() != null ? user : null;
    }
}
