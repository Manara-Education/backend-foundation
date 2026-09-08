package com.manara.backend.session.manager;

import com.manara.backend.user.model.User;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class HttpSessionManager implements SessionManager {

    private static final String SESSION_COOKIE = "MANARA_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private final SecurityContextRepository securityContextRepository;

    @Override
    public void establish(User user, HttpServletRequest request, HttpServletResponse response) {
        var auth = UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities());
        establish(auth, request, response);
    }

    @Override
    public void establish(Authentication auth,
                          HttpServletRequest request,
                          HttpServletResponse response) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        request.getSession(true);
        request.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        stampAuthVersion(request, auth);
    }

    /**
     * Records the epoch this session is being opened under.
     *
     * <p>The number comes from the principal because the principal was read from the database
     * moments ago, on this request — by the authentication provider on sign-in, or by the caller
     * that re-read the row after bumping it. It is never read from a session, so it cannot inherit
     * a stale value from the session being replaced.
     *
     * <p>A principal that is not one of ours leaves the session unstamped, and an unstamped session
     * is refused on its next request. That is the safe direction: the alternative — stamping
     * something plausible — would mint a session nobody can prove the epoch of.
     */
    private void stampAuthVersion(HttpServletRequest request, Authentication auth) {
        if (auth.getPrincipal() instanceof User user) {
            // Fetched after changeSessionId and after saveContext deliberately: this is the session
            // the response's cookie will actually name.
            request.getSession().setAttribute(AUTH_VERSION_ATTRIBUTE, user.getAuthVersion());
        }
    }

    @Override
    public void terminate(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        clearCookie(response, SESSION_COOKIE);
        clearCookie(response, CSRF_COOKIE);
    }

    private void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, null);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
