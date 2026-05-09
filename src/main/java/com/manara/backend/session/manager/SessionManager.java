package com.manara.backend.session.manager;

import com.manara.backend.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

public interface SessionManager {

    /** Re-authenticates the principal directly (post-OTP), then promotes the session. */
    void establish(User user, HttpServletRequest request, HttpServletResponse response);

    /**
     * Promotes an authenticated principal into a server-side session:
     *   1. invalidate any pre-auth session (defense in depth on top of changeSessionId)
     *   2. force a fresh session id via {@link HttpServletRequest#changeSessionId()} to defeat fixation
     *   3. persist the SecurityContext into the session so subsequent requests resolve auth from cookie
     */
    void establish(Authentication auth, HttpServletRequest request, HttpServletResponse response);

    /** Tears down the current session: invalidate, clear context, expire session + CSRF cookies. */
    void terminate(HttpServletRequest request, HttpServletResponse response);
}
