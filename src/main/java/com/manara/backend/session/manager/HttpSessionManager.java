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
    private final SessionCeiling sessionCeiling;

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
        countAgainstCeiling(request, auth);
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

    /**
     * Puts the new session under its account's ceiling — which, if the account already holds as many
     * sessions as it may, ends the oldest of them.
     *
     * <p>Only recorded here. The session is not in the store until the response is committed, and
     * {@link SessionAdmissionFilter} has it counted then; {@link SessionCeiling} explains why counting
     * any sooner would let a racing sign-in leave a session uncounted. Every way of signing in —
     * password, emailed code, the re-issue after a password change — comes through this method, so
     * none of them is exempt.
     *
     * <p>The session replaced above needs no bookkeeping of its own: it is already gone from the store,
     * and the count drops sessions that are gone before it counts.
     */
    private void countAgainstCeiling(HttpServletRequest request, Authentication auth) {
        if (auth.getPrincipal() instanceof User user && user.getId() != null) {
            sessionCeiling.deferAdmission(request, user.getId());
        }
    }

    @Override
    public void terminate(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            String sessionId = session.getId();
            session.invalidate();
            releaseSlot(sessionId);
        }
        SecurityContextHolder.clearContext();
        clearCookie(response, SESSION_COOKIE);
        clearCookie(response, CSRF_COOKIE);
    }

    /**
     * Gives the ended session's place under the ceiling back to its account. The account is read from
     * the context before it is cleared, where it is the ended session's own principal: both callers —
     * sign-out, and the refusal of a stale session — run on a request that session authenticated.
     */
    private void releaseSlot(String sessionId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user && user.getId() != null) {
            sessionCeiling.release(user.getId(), sessionId);
        }
    }

    private void clearCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, null);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setHttpOnly(true);
        response.addCookie(cookie);
    }
}
