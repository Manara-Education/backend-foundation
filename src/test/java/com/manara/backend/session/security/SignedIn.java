package com.manara.backend.session.security;

import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.user.model.User;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * A request from an account that is genuinely signed in, for tests that do not want to walk the
 * sign-in flow to get there.
 *
 * <p>Spring Security's own {@code user(...)} post-processor puts a principal in the security
 * context and stops, which was a complete description of a signed-in request until sessions started
 * carrying the authentication epoch they were opened under. It is not one any more: a session with
 * a principal and no epoch is precisely the shape of a session left over from before that change,
 * and {@link SessionAuthenticationFreshnessFilter} refuses it — correctly, and by design.
 *
 * <p>So this adds the missing half. It is not a way around the filter: the stamp it writes is the
 * account's real {@code authVersion}, so a test whose account has since had its password changed
 * still gets the 401 it should. It only stops a test from failing for the one reason that has
 * nothing to do with what it is testing.
 */
public final class SignedIn {

    private SignedIn() {
    }

    public static RequestPostProcessor signedIn(User account) {
        RequestPostProcessor principal = user(account);
        return request -> {
            var processed = principal.postProcessRequest(request);
            processed.getSession()
                    .setAttribute(SessionManager.AUTH_VERSION_ATTRIBUTE, account.getAuthVersion());
            return processed;
        };
    }
}
