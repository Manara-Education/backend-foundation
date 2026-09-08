package com.manara.backend.session.manager;

import com.manara.backend.user.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;

public interface SessionManager {

    /**
     * Session attribute holding the account's authentication epoch at the moment this session was
     * established — {@code users.auth_version} as it stood, a {@code Long}.
     *
     * <p>It lives on the session rather than inside the principal for two reasons. A value carried
     * by the principal would be part of the same snapshot it is supposed to be checking, and
     * refreshing the principal would refresh the check along with it. And a session written by a
     * build that predates this attribute simply does not have it, which is how those sessions come
     * to be treated as stale without a single command being run against the session store.
     *
     * <p>Absent is not zero. An unstamped session is refused; only a stamp equal to the current row
     * is honoured.
     */
    String AUTH_VERSION_ATTRIBUTE = "MANARA_AUTH_VERSION";

    /** Re-authenticates the principal directly (post-OTP), then promotes the session. */
    void establish(User user, HttpServletRequest request, HttpServletResponse response);

    /**
     * Promotes an authenticated principal into a server-side session:
     *   1. invalidate any pre-auth session (defense in depth on top of changeSessionId)
     *   2. force a fresh session id via {@link HttpServletRequest#changeSessionId()} to defeat fixation
     *   3. persist the SecurityContext into the session so subsequent requests resolve auth from cookie
     *   4. stamp {@link #AUTH_VERSION_ATTRIBUTE} with the epoch the principal was just read under,
     *      so the session can be told apart from one opened under a credential since replaced
     */
    void establish(Authentication auth, HttpServletRequest request, HttpServletResponse response);

    /** Tears down the current session: invalidate, clear context, expire session + CSRF cookies. */
    void terminate(HttpServletRequest request, HttpServletResponse response);
}
