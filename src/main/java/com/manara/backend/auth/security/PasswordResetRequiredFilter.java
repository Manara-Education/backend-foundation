package com.manara.backend.auth.security;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
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
 * The flag is read from the database on each request, not from the session principal. That
 * principal is a snapshot taken when the session was established, so it goes stale in both
 * directions -- it would keep locking the account out after the password had been changed, and
 * would keep letting an already-open session through after an operator flagged the account.
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

    private final UserRepository userRepository;
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

    public PasswordResetRequiredFilter(UserRepository userRepository,
                                       MessageService messageService,
                                       ObjectMapper objectMapper,
                                       List<PublicEndpointContribution> publicEndpointContributions) {
        this.userRepository = userRepository;
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

        Long userId = authenticatedUserId();

        if (userId == null
                || ALLOWED_WHILE_RESET_REQUIRED.matches(request)
                || publicEndpoints.matches(request)
                || !userRepository.existsByIdAndRequiresPasswordResetTrue(userId)) {
            filterChain.doFilter(request, response);
            return;
        }

        reject(response);
    }

    /**
     * The id of the signed-in user, or {@code null} when this request is anonymous or its
     * principal is not one of ours (which is every unauthenticated and every pre-auth call --
     * those are somebody else's concern).
     */
    private Long authenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getPrincipal() instanceof User user ? user.getId() : null;
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
