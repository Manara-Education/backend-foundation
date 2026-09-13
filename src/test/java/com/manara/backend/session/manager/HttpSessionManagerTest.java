package com.manara.backend.session.manager;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cookies sign-out writes to expire the session and CSRF cookies.
 *
 * <p>They carry {@code Secure} exactly when the request did, which is the rule the cookies they
 * replace were issued under: over HTTPS nothing about the expiring cookie may travel in clear, and
 * over plain HTTP in development a {@code Secure} cookie would be refused by the browser and the
 * old cookies would outlive the sign-out.
 */
@ExtendWith(MockitoExtension.class)
class HttpSessionManagerTest {

    @Mock
    private SecurityContextRepository securityContextRepository;

    @Mock
    private SessionCeiling sessionCeiling;

    @InjectMocks
    private HttpSessionManager sessionManager;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("over HTTPS, the cookies that end a session are marked Secure")
    void expiringCookiesAreSecureOverHttps() {
        Cookie[] cookies = terminate(true);

        assertThat(cookies).extracting(Cookie::getName)
                .containsExactlyInAnyOrder("MANARA_SESSION", "XSRF-TOKEN");
        assertThat(cookies).allSatisfy(cookie -> {
            assertThat(cookie.getSecure()).isTrue();
            assertThat(cookie.getMaxAge()).isZero();
            assertThat(cookie.getPath()).isEqualTo("/");
        });
    }

    @Test
    @DisplayName("over plain HTTP, they are not — the browser would refuse them and the old cookies would survive")
    void expiringCookiesAreNotSecureOverHttp() {
        Cookie[] cookies = terminate(false);

        assertThat(cookies).extracting(Cookie::getName)
                .containsExactlyInAnyOrder("MANARA_SESSION", "XSRF-TOKEN");
        assertThat(cookies).allSatisfy(cookie -> {
            assertThat(cookie.getSecure()).isFalse();
            assertThat(cookie.getMaxAge()).isZero();
        });
    }

    private Cookie[] terminate(boolean secure) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(secure);
        MockHttpServletResponse response = new MockHttpServletResponse();

        sessionManager.terminate(request, response);

        return response.getCookies();
    }
}
