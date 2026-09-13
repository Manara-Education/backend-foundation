package com.manara.backend.common.security.ratelimit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter with its Redis genuinely unreachable: a real Lettuce client pointed at a port
 * nothing listens on, behind the real filter and the application's own rules. What is measured is
 * how many requests get past the filter to the endpoint, which is the question an outage raises.
 */
class RateLimitOutageTest {

    private static LettuceConnectionFactory deadRedis;
    private static RateLimitFilter filter;

    @BeforeAll
    static void pointAtAPortNothingListensOn() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        deadRedis = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", closedPort),
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500)).build());
        deadRedis.afterPropertiesSet();
        var limiter = new RateLimiter(new StringRedisTemplate(deadRedis));
        filter = new RateLimitFilter(limiter, new RateLimitProperties(true, null).rules());
    }

    @AfterAll
    static void close() {
        deadRedis.destroy();
    }

    @Test
    @DisplayName("sign-in is refused with 503 before a single password is checked")
    void signInIsRefusedBeforeAuthentication() throws Exception {
        var reached = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            MockHttpServletResponse response = post("/api/v1/auth/login", "198.51.100.10", reached);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        }
        assertThat(reached).as("login requests that reached authentication").hasValue(0);
    }

    @Test
    @DisplayName("email verification is refused too, so no code is spent on a sign-in that cannot finish")
    void otpVerificationIsRefused() throws Exception {
        var reached = new AtomicInteger();
        assertThat(post("/api/v1/auth/verify-otp", "198.51.100.11", reached).getStatus()).isEqualTo(503);
        assertThat(reached).hasValue(0);
    }

    @Test
    @DisplayName("registration is still limited, per instance, to its configured allowance")
    void registrationIsStillLimited() throws Exception {
        var reached = new AtomicInteger();
        int limited = 0;
        for (int i = 0; i < 12; i++) {
            if (post("/api/v1/auth/register", "198.51.100.12", reached).getStatus() == 429) {
                limited++;
            }
        }
        assertThat(reached).as("registrations let through").hasValue(5);
        assertThat(limited).isEqualTo(7);
    }

    @Test
    @DisplayName("password recovery keeps working during an outage, within its allowance")
    void passwordRecoveryStillWorksWithinItsAllowance() throws Exception {
        var reached = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            post("/api/v1/auth/forgot-password", "198.51.100.13", reached);
        }
        assertThat(reached).hasValue(5);
    }

    @Test
    @DisplayName("requests no rule covers are untouched")
    void unratedRequestsPassThrough() throws Exception {
        var reached = new AtomicInteger();
        post("/api/v1/terms/current", "198.51.100.14", reached);
        assertThat(reached).hasValue(1);
    }

    private static MockHttpServletResponse post(String path, String client, AtomicInteger reached) throws Exception {
        var request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(client);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> reached.incrementAndGet());
        return response;
    }
}
