package com.manara.backend.auth.integration;

import com.manara.backend.ManaraBackendApplication;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.session.manager.SessionCeiling;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-F07. How many sessions one account may hold at once, and which one goes when it signs in again.
 *
 * <p>The limit used to be a {@code maximumSessions(5)} line in the security configuration that
 * nothing ever consulted: sign-in is custom and never runs Spring Security's concurrency control,
 * so six sign-ins produced six live sessions. What these tests pin down is the behaviour, not the
 * configuration — five usable sessions, the oldest ending when a sixth is opened — through every
 * way a session is opened and across more than one application instance.
 *
 * <p>Both instances are real servers, reached over real HTTP, rather than MockMvc. The existing
 * session tests add Spring Session's filter to MockMvc by hand, which is enough to carry a cookie,
 * but MockMvc then runs exactly the filters the test named in the order the test named them. The
 * question here is what the servlet container assembles in production, so the container assembles
 * it. The second instance is a separate application context sharing only PostgreSQL and Redis with
 * the first, which is all two production replicas share.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Many sign-ins from one address within seconds is precisely what the login rate limit
        // exists to stop. It is not what is under test, and it would answer 429 long before the
        // ceiling had anything to decide.
        properties = "app.rate-limit.enabled=false")
class SessionCeilingTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@ceiling.example";
    private static final String EMAIL = "noor" + DOMAIN;
    private static final String PASSWORD = "Ceiling fixture passphrase 1!";
    private static final String NEW_PASSWORD = "Ceiling fixture rotated passphrase 2!";
    private static final int CEILING = 5;
    private static final String SESSION_COOKIE = "MANARA_SESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Started on first use and shared by every test in the class; a context is slow to boot. */
    private static ConfigurableApplicationContext secondInstance;

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    private Instance instanceA;
    private Instance instanceB;
    private String csrfToken;

    /** Every session a test was handed, so the store can be left as it was found. */
    private final List<Device> issued = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startInstancesAndRegister() {
        removeTestAccounts();
        instanceA = new Instance("A", URI.create("http://localhost:" + port));
        instanceB = new Instance("B", URI.create("http://localhost:" + secondInstancePort()));
        csrfToken = fetchCsrfToken(instanceA);

        register(EMAIL);
        // Verified in SQL rather than through the emailed code: verify-otp is itself a sign-in, and
        // would put a session on the account before a test had started counting.
        jdbc.update("UPDATE users SET email_verified = true WHERE email = ?", EMAIL);
    }

    @AfterEach
    void removeTestAccounts() {
        issued.forEach(device -> redis.delete(SessionCeiling.sessionKey(device.sessionId())));
        issued.clear();
        jdbc.queryForList("SELECT id FROM users WHERE email LIKE ?", Long.class, "%" + DOMAIN)
                .forEach(accountId -> redis.delete(SessionCeiling.indexKey(accountId)));

        jdbc.update("DELETE FROM terms_acceptances WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM instructors WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @AfterAll
    static void stopSecondInstance() {
        if (secondInstance != null) {
            secondInstance.close();
            secondInstance = null;
        }
    }

    // ── The finding itself ────────────────────────────────────────────────────

    @Test
    @DisplayName("a sixth sign-in ends the oldest session and leaves exactly five")
    void theSixthSignInEndsTheOldest() {
        List<Device> devices = signInRepeatedly(instanceA, CEILING + 1);
        assertThat(devices).extracting(Device::cookie).doesNotHaveDuplicates();
        List<Device> survivors = devices.subList(1, devices.size());
        Device oldest = devices.getFirst();

        assertThat(me(instanceA, oldest)).as("the oldest session").isEqualTo(401);
        assertUsable(instanceA, survivors);

        // The register holds the survivors in the order they were opened, the evicted session is
        // gone from the store rather than merely unlisted, and the register cannot outlive them forever.
        assertThat(registered()).containsExactlyElementsOf(sessionIds(survivors));
        assertThat(redis.hasKey(SessionCeiling.sessionKey(oldest.sessionId()))).isFalse();
        assertThat(redis.getExpire(SessionCeiling.indexKey(accountId()))).isPositive();

        // Replayed, the evicted cookie is nobody — on a read, and on a write that would matter.
        assertThat(me(instanceA, oldest)).isEqualTo(401);
        assertThat(changePassword(instanceA, oldest, PASSWORD, NEW_PASSWORD).statusCode()).isEqualTo(401);
        assertThat(authVersion()).as("the refused change must not have happened").isZero();
    }

    @Test
    @DisplayName("ten simultaneous sign-ins, spread over two instances, leave exactly five sessions")
    void simultaneousSignInsLeaveExactlyTheCeiling() throws Exception {
        int attempts = 2 * CEILING;
        CountDownLatch go = new CountDownLatch(1);
        List<Device> devices = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            List<Future<Device>> pending = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                Instance target = i % 2 == 0 ? instanceA : instanceB;
                pending.add(pool.submit(() -> {
                    go.await();
                    return signIn(target);
                }));
            }
            go.countDown();
            for (Future<Device> device : pending) {
                devices.add(device.get(2, TimeUnit.MINUTES));
            }
        }

        List<Device> usableOnA = usable(instanceA, devices);
        assertThat(usableOnA).hasSize(CEILING);
        assertThat(usable(instanceB, devices))
                .as("both instances must agree on which five survived")
                .containsExactlyInAnyOrderElementsOf(usableOnA);
        assertThat(registered())
                .as("and the register must list exactly those five, no more and no fewer")
                .containsExactlyInAnyOrderElementsOf(sessionIds(usableOnA));
    }

    @Test
    @DisplayName("the ceiling is one ceiling across instances, and both agree on who was evicted")
    void theCeilingSpansInstances() {
        Device mintedByA = signIn(instanceA);
        Device mintedByB = signIn(instanceB);
        List<Device> rest = List.of(signIn(instanceA), signIn(instanceB), signIn(instanceA));

        // Every session is honoured by the instance that did not mint it, too.
        List<Device> five = plus(List.of(mintedByA, mintedByB), rest.toArray(Device[]::new));
        assertUsable(instanceA, five);
        assertUsable(instanceB, five);

        // A sign-in on B evicts the session A minted, and neither instance honours it afterwards.
        Device sixth = signIn(instanceB);
        assertThat(me(instanceB, mintedByA)).isEqualTo(401);
        assertThat(me(instanceA, mintedByA)).isEqualTo(401);

        // And the other way round.
        Device seventh = signIn(instanceA);
        assertThat(me(instanceA, mintedByB)).isEqualTo(401);
        assertThat(me(instanceB, mintedByB)).isEqualTo(401);

        List<Device> survivors = plus(rest, sixth, seventh);
        assertUsable(instanceA, survivors);
        assertUsable(instanceB, survivors);
    }

    // ── Every way a session is opened counts ─────────────────────────────────

    @Test
    @DisplayName("the session opened by verifying an email address counts toward the ceiling")
    void emailVerificationSignInCounts() {
        String email = "layla" + DOMAIN;
        register(email);

        Device verified = verifyEmail(instanceA, email);
        assertThat(me(instanceA, verified)).isEqualTo(200);

        List<Device> later = signInRepeatedly(instanceB, email, PASSWORD, CEILING);

        assertThat(me(instanceA, verified)).as("the verification session was the oldest").isEqualTo(401);
        assertUsable(instanceA, later);
    }

    @Test
    @DisplayName("the session a password change re-issues counts toward the ceiling")
    void passwordChangeReissueCounts() {
        List<Device> devices = signInRepeatedly(instanceA, CEILING);
        Device caller = devices.getFirst();

        HttpResponse<String> changed = changePassword(instanceA, caller, PASSWORD, NEW_PASSWORD);
        assertThat(changed.statusCode()).as(changed.body()).isEqualTo(200);
        Device reissued = issuedDevice(instanceA, changed);
        assertThat(reissued.cookie()).isNotEqualTo(caller.cookie());

        // What a password change always did, untouched: the caller carries on under a new session,
        // and every other device is refused with the revocation code.
        assertThat(me(instanceA, caller)).isEqualTo(401);
        for (Device other : devices.subList(1, devices.size())) {
            HttpResponse<String> refused = call(instanceA, "GET", "/api/v1/auth/me", other, null);
            assertThat(refused.statusCode()).isEqualTo(401);
            assertThat(refused.body()).contains("SESSION_REVOKED");
        }
        assertThat(me(instanceA, reissued)).isEqualTo(200);
        assertThat(registered())
                .as("the revoked sessions gave their places back as they were torn down")
                .containsExactly(reissued.sessionId());

        // The re-issued session and four sign-ins are exactly the ceiling, so nothing goes yet...
        List<Device> fresh = signInRepeatedly(instanceA, EMAIL, NEW_PASSWORD, CEILING - 1);
        assertUsable(instanceA, plus(fresh, reissued));

        // ...and the next sign-in ends the re-issued session, because it is now the oldest.
        Device overflow = signIn(instanceA, EMAIL, NEW_PASSWORD, null);
        assertThat(me(instanceA, reissued)).isEqualTo(401);
        assertUsable(instanceA, plus(fresh, overflow));
    }

    // ── Rotation and sign-out give slots back ────────────────────────────────

    @Test
    @DisplayName("signing out gives the slot back")
    void signingOutFreesASlot() {
        List<Device> devices = signInRepeatedly(instanceA, CEILING);
        Device leaving = devices.get(2);

        // Through the other instance, so the slot is given back in the shared store and not locally.
        assertThat(call(instanceB, "POST", "/api/v1/auth/logout", leaving, null).statusCode()).isEqualTo(200);
        assertThat(me(instanceA, leaving)).isEqualTo(401);
        assertThat(registered())
                .as("given back at sign-out, not left for a later count to notice")
                .hasSize(CEILING - 1)
                .doesNotContain(leaving.sessionId());

        // Nothing is evicted: the slot the sign-out freed is the one this sign-in takes.
        Device replacement = signIn(instanceA);
        List<Device> remaining = devices.stream().filter(device -> device != leaving).toList();
        assertUsable(instanceA, plus(remaining, replacement));

        // With every slot taken again, the oldest goes.
        signIn(instanceA);
        assertThat(me(instanceA, devices.getFirst())).isEqualTo(401);
    }

    @Test
    @DisplayName("signing in again from the same browser replaces its session rather than taking a second slot")
    void signingInAgainReplacesTheBrowsersSession() {
        Device browser = signIn(instanceA);
        Device again = signIn(instanceA, EMAIL, PASSWORD, browser);

        assertThat(again.cookie()).isNotEqualTo(browser.cookie());
        assertThat(me(instanceA, browser)).as("rotation: the replaced session is gone").isEqualTo(401);

        List<Device> others = signInRepeatedly(instanceA, CEILING - 1);
        assertUsable(instanceA, plus(others, again));
    }

    // ── The assumption the enforcement rests on ──────────────────────────────

    /**
     * The ceiling reads and deletes Spring Session's keys directly. If the repository ever stores a
     * session somewhere else, every session would look already ended to the count and nothing would
     * ever be evicted — silently. This is the test that makes that loud.
     */
    @Test
    @DisplayName("sessions are stored under the key the ceiling reads and deletes")
    void sessionsLiveWhereTheCeilingLooks() {
        Device device = signIn(instanceA);

        assertThat(redis.hasKey(SessionCeiling.sessionKey(device.sessionId()))).isTrue();
        assertThat(registered()).containsExactly(device.sessionId());
    }

    // ------------------------------------------------------------------ helpers

    private record Instance(String name, URI base) {
    }

    /** A signed-in device, held the way a browser holds one: by its session cookie and nothing else. */
    private record Device(Instance mintedBy, String cookie) {

        /** Spring Session's cookie carries the session id base64-encoded. */
        String sessionId() {
            return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
        }
    }

    private int secondInstancePort() {
        if (secondInstance == null) {
            // Command-line arguments rather than builder default properties: defaults rank below
            // application.properties, which would point this instance at localhost instead of the
            // containers the first instance is using.
            secondInstance = new SpringApplicationBuilder(ManaraBackendApplication.class).run(
                    "--server.port=0",
                    "--spring.datasource.url=" + environment.getRequiredProperty("spring.datasource.url"),
                    "--spring.datasource.username=" + environment.getRequiredProperty("spring.datasource.username"),
                    "--spring.datasource.password=" + environment.getRequiredProperty("spring.datasource.password"),
                    "--spring.datasource.hikari.maximum-pool-size=4",
                    "--spring.data.redis.host=" + environment.getRequiredProperty("spring.data.redis.host"),
                    "--spring.data.redis.port=" + environment.getRequiredProperty("spring.data.redis.port"),
                    "--app.rate-limit.enabled=false",
                    "--spring.jpa.show-sql=false",
                    "--spring.main.banner-mode=off");
        }
        return ((WebServerApplicationContext) secondInstance).getWebServer().getPort();
    }

    /**
     * The token the SPA would fetch on load. It is a double-submit cookie bound to neither a session
     * nor an instance, so one token serves every request below, on both instances.
     */
    private String fetchCsrfToken(Instance instance) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(instance.base().resolve("/api/v1/auth/csrf"))
                .GET()
                .build());
        return cookieValue(response, CSRF_COOKIE)
                .orElseThrow(() -> new AssertionError("the CSRF endpoint must issue a token cookie"));
    }

    private void register(String email) {
        // Always through the first instance: it is the one whose email service is stubbed.
        HttpResponse<String> response = call(instanceA, "POST", "/api/v1/auth/register", null, """
                {"fullName":"Ceiling Test","email":"%s","password":"%s","role":"STUDENT",
                 "termsAccepted":true,"termsVersion":"%s"}"""
                .formatted(email, PASSWORD, termsVersionRegistry.current().orElseThrow().id()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private Device verifyEmail(Instance instance, String email) {
        HttpResponse<String> response = call(instance, "POST", "/api/v1/auth/verify-otp", null, """
                {"email":"%s","code":"%s"}""".formatted(email, outstandingCode(email)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return issuedDevice(instance, response);
    }

    private Device signIn(Instance instance) {
        return signIn(instance, EMAIL, PASSWORD, null);
    }

    private Device signIn(Instance instance, String email, String password, Device presenting) {
        HttpResponse<String> response = call(instance, "POST", "/api/v1/auth/login", presenting, """
                {"email":"%s","password":"%s"}""".formatted(email, password));
        assertThat(response.statusCode()).as("sign-in on %s: %s", instance.name(), response.body()).isEqualTo(200);
        return issuedDevice(instance, response);
    }

    private List<Device> signInRepeatedly(Instance instance, int times) {
        return signInRepeatedly(instance, EMAIL, PASSWORD, times);
    }

    private List<Device> signInRepeatedly(Instance instance, String email, String password, int times) {
        List<Device> devices = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            devices.add(signIn(instance, email, password, null));
        }
        return devices;
    }

    private HttpResponse<String> changePassword(Instance instance, Device device, String current, String replacement) {
        return call(instance, "POST", "/api/v1/auth/change-password", device, """
                {"currentPassword":"%s","newPassword":"%s"}""".formatted(current, replacement));
    }

    private int me(Instance instance, Device device) {
        return call(instance, "GET", "/api/v1/auth/me", device, null).statusCode();
    }

    private List<Device> usable(Instance instance, List<Device> devices) {
        return devices.stream().filter(device -> me(instance, device) == 200).toList();
    }

    private void assertUsable(Instance instance, List<Device> devices) {
        for (Device device : devices) {
            assertThat(me(instance, device))
                    .as("session minted by %s, presented to %s", device.mintedBy().name(), instance.name())
                    .isEqualTo(200);
        }
    }

    private Device issuedDevice(Instance instance, HttpResponse<String> response) {
        Device device = new Device(instance, cookieValue(response, SESSION_COOKIE)
                .orElseThrow(() -> new AssertionError(
                        "a successful sign-in must issue a session cookie, or this test proves nothing")));
        issued.add(device);
        return device;
    }

    private HttpResponse<String> call(Instance instance, String method, String path, Device device, String json) {
        String cookies = CSRF_COOKIE + "=" + csrfToken
                + (device == null ? "" : "; " + SESSION_COOKIE + "=" + device.cookie());
        HttpRequest.Builder request = HttpRequest.newBuilder(instance.base().resolve(path))
                .timeout(Duration.ofSeconds(60))
                .header("Cookie", cookies)
                .header("X-XSRF-TOKEN", csrfToken);
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static Optional<String> cookieValue(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(name + "="))
                .map(header -> header.substring(name.length() + 1).split(";", 2)[0])
                .filter(value -> !value.isEmpty())
                .findFirst();
    }

    private static List<Device> plus(List<Device> devices, Device... more) {
        return Stream.concat(devices.stream(), Stream.of(more)).toList();
    }

    private static List<String> sessionIds(List<Device> devices) {
        return devices.stream().map(Device::sessionId).toList();
    }

    /** The account's register, oldest first. */
    private Set<String> registered() {
        return redis.opsForZSet().range(SessionCeiling.indexKey(accountId()), 0, -1);
    }

    private long accountId() {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
    }

    private Long authVersion() {
        return jdbc.queryForObject("SELECT auth_version FROM users WHERE email = ?", Long.class, EMAIL);
    }

    /** The code the application generated and would have emailed. Never exposed by any API. */
    private String outstandingCode(String email) {
        return jdbc.queryForObject("""
                SELECT o.code FROM otps o
                  JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.used = false
                 ORDER BY o.created_at DESC
                 LIMIT 1
                """, String.class, email);
    }
}
