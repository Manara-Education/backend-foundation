package com.manara.backend.auth.security;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.user.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * Refuses every request from an account that still owes a password change, except the handful
 * of calls it needs to get out of that state.
 *
 * The route guard in the client already redirects such a user to the change-password screen.
 * This is the half that does not depend on the client behaving: without it, "you must change
 * your password" would be a suggestion that any HTTP client could decline.
 *
 * The flag still may not be read from the sign-in snapshot -- it goes stale in both directions,
 * locking an account out after the password has been changed and letting an already-open session
 * through after an operator flags the account. What changed is where the current value comes from.
 * It used to be a query this filter issued itself; it is now read off the principal, because
 * {@code SessionAuthenticationFreshnessFilter} has already replaced that principal with one built
 * from a row read on this request, earlier in the chain. The guarantee is the same and the account
 * is read once per request rather than twice.
 *
 * That makes the ordering load-bearing: this filter is correct only while it runs after the
 * freshness filter, and the set of requests it inspects is a subset of the set that filter
 * refreshes. Moving either one is a security change, not a tidy-up.
 */
@Component
public class PasswordResetRequiredFilter extends OncePerRequestFilter {

    /**
     * The signed-in endpoints an account may still reach while it owes the change: the one that
     * ends the state, the one that reports it, and the way out.
     */
    private static final RequestMatcher ALLOWED_WHILE_RESET_REQUIRED = new OrRequestMatcher(
            matcher(HttpMethod.POST, "/api/v1/auth/change-password"),
            matcher(HttpMethod.POST, "/api/v1/auth/logout"),
            matcher(HttpMethod.GET, "/api/v1/auth/me"));

    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    /**
     * Every endpoint the application serves without a session, collected from the same
     * contributions the filter chain builds its permitAll rules from.
     *
     * They are exempt by definition: an anonymous caller may already reach all of them, so
     * refusing them to a signed-in account would withhold nothing and only strand a user who
     * happens to still hold a session -- the CSRF seed and the emailed-code recovery flow
     * among them. Derived rather than listed so a public endpoint added later cannot fall
     * through the gap between two allowlists.
     */
    private final RequestMatcher publicEndpoints;

    public PasswordResetRequiredFilter(MessageService messageService,
                                       ObjectMapper objectMapper,
                                       List<PublicEndpointContribution> publicEndpointContributions) {
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

        User user = authenticatedUser();

        if (user == null
                || ALLOWED_WHILE_RESET_REQUIRED.matches(request)
                || publicEndpoints.matches(request)
                || !user.isRequiresPasswordReset()) {
            filterChain.doFilter(request, response);
            return;
        }

        reject(response);
    }

    /**
     * The signed-in user, or {@code null} when this request is anonymous or its principal is not
     * one of ours (which is every unauthenticated and every pre-auth call -- those are somebody
     * else's concern).
     *
     * <p>On every request that reaches the flag check below, this principal was built by
     * {@code SessionAuthenticationFreshnessFilter} from a row read on this request. Its exemptions
     * are a subset of that filter's, so there is no path on which the flag is consulted and the
     * principal is still the sign-in snapshot.
     */
    private User authenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getPrincipal() instanceof User user ? user : null;
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getWriter(),
                ApiResponse.error(messageService.get("auth.password.resetRequired")));
    }

    private static RequestMatcher matcher(HttpMethod method, String pattern) {
        return PathPatternRequestMatcher.withDefaults().matcher(method, pattern);
    }
}
