package com.manara.backend.common.exception;

import com.jayway.jsonpath.JsonPath;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import jakarta.servlet.http.Cookie;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * What a client is told when the request itself is at fault.
 *
 * <p>A local penetration test (2026-09-10) found {@code GET /api/v1/instructor/courses/1%20OR%201%3D1}
 * and {@code GET /api/v1/student/courses/1?mode=INVALID} answered 500. Nothing was injected — the
 * value fails conversion before any controller runs, so no query ever saw it — but the catch-all in
 * {@link GlobalExceptionHandler} also caught every exception Spring MVC raises for a malformed
 * request, and reported each one as a server fault with an ERROR stack trace. Each probe below is one
 * of those exceptions, sent the way a client would cause it. The last group proves the catch-all
 * still answers 500 for what genuinely is one.
 *
 * <p>Runs on a real port as well as through MockMvc, in one context. MockMvc hands the application a
 * multipart request that is already parsed, so upload limits and malformed bodies cannot happen
 * there; only Tomcat reading real bytes shows what an uploading client receives.
 *
 * <p>Nothing is cleaned up: every account has an address of its own and nothing asserts about others.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Import(ClientErrorResponseTest.ServerFaults.class)
class ClientErrorResponseTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@client-errors.example";
    private static final String PASSWORD = "client-error-probe-passphrase";
    private static final String BOUNDARY = "x01-probe-boundary";
    private static final String MULTIPART = "multipart/form-data; boundary=" + BOUNDARY;
    private static final int MB = 1024 * 1024;

    private static final String UNEXPECTED = "An unexpected error occurred";
    private static final String MALFORMED =
            "The request body could not be read. Please check the format of the submitted values.";
    private static final String TOO_LARGE = "The upload is larger than the maximum allowed size.";
    private static final String MEDIA_TYPE_UNSUPPORTED = "The content type of this request is not supported.";

    @Autowired private WebApplicationContext context;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private MessageSource messageSource;

    @Value("${local.server.port}")
    private int port;

    private MockMvc mockMvc;

    /**
     * The instructor's real session, shared by every real-port test. Signing in once rather than per
     * test keeps the class inside the login rate limit, which is live here and keyed on the single
     * address all of these requests come from.
     */
    private Browser instructorBrowser;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ── The findings ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("the reported probes are 400 over the real port, exactly as they were sent")
    void theReportedProbesAreBadRequests() throws Exception {
        Browser browser = instructor();

        browser.send(browser.request("/api/v1/instructor/courses/1%20OR%201%3D1").GET())
                .isError(HttpStatus.BAD_REQUEST, "The value given for \"courseId\" is not valid.")
                .doesNotEcho("OR 1");
        browser.send(browser.request("/api/v1/student/courses/1?mode=INVALID").GET())
                .isError(HttpStatus.BAD_REQUEST, "The value given for \"mode\" is not valid.")
                .doesNotEcho("INVALID");
    }

    @Test
    @DisplayName("a path variable that is not a number is 400, and the value is not echoed")
    void aNonNumericPathVariableIsABadRequest() throws Exception {
        perform(get(URI.create("/api/v1/instructor/courses/1%20OR%201%3D1"))
                .with(signedIn(account(Role.INSTRUCTOR))))
                .isError(HttpStatus.BAD_REQUEST, "The value given for \"courseId\" is not valid.")
                .doesNotEcho("OR 1");
    }

    @Test
    @DisplayName("an id too large for its type is 400")
    void anOverflowingIdIsABadRequest() throws Exception {
        perform(get("/api/v1/student/courses/99999999999999999999?mode=ENROLLED")
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.BAD_REQUEST, "The value given for \"courseId\" is not valid.");
    }

    @Test
    @DisplayName("an enum query parameter with an unknown value is 400")
    void anUnknownEnumValueIsABadRequest() throws Exception {
        perform(get("/api/v1/student/courses/1?mode=INVALID")
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.BAD_REQUEST, "The value given for \"mode\" is not valid.")
                .doesNotEcho("INVALID");
    }

    @Test
    @DisplayName("a missing required query parameter is 400")
    void aMissingRequiredParameterIsABadRequest() throws Exception {
        perform(get("/api/v1/student/courses/1")
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.BAD_REQUEST, "The required parameter \"mode\" is missing.");
    }

    // ── Method and media type ────────────────────────────────────────────────

    @Test
    @DisplayName("an unsupported method is 405 and says which methods are")
    void anUnsupportedMethodIsMethodNotAllowed() throws Exception {
        perform(put("/api/v1/student/courses")
                .with(xsrf()).with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.METHOD_NOT_ALLOWED, "This request method is not supported here.")
                .hasHeaderContaining(HttpHeaders.ALLOW, "GET");
    }

    @Test
    @DisplayName("a body in a content type the endpoint does not read is 415 and says which it does")
    void anUnsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        perform(post("/api/v1/instructor/courses")
                .contentType(MediaType.TEXT_PLAIN).content("title=Nope")
                .with(xsrf()).with(signedIn(account(Role.INSTRUCTOR))))
                .isError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MEDIA_TYPE_UNSUPPORTED)
                .hasHeaderContaining(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("asking for a response format the API cannot produce is 406, answered in JSON")
    void anUnacceptableResponseFormatIsNotAcceptable() throws Exception {
        perform(get("/api/v1/student/courses")
                .accept(MediaType.parseMediaType("text/csv"))
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.NOT_ACCEPTABLE,
                        "The response cannot be produced in a format this request accepts.");
    }

    // ── Uploads ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an upload without its file part is 400")
    void anUploadWithoutItsFilePartIsABadRequest() throws Exception {
        perform(multipart("/api/v1/uploads")
                .file(new MockMultipartFile("attachment", "cover.png", "image/png", new byte[16]))
                .with(xsrf()).with(signedIn(account(Role.INSTRUCTOR))))
                .isError(HttpStatus.BAD_REQUEST, "The required part \"file\" is missing from the request.");
    }

    @Test
    @DisplayName("an upload that is not multipart at all is 415 and names the type it needs")
    void anUploadThatIsNotMultipartIsUnsupportedMediaType() throws Exception {
        perform(post("/api/v1/uploads")
                .contentType(MediaType.APPLICATION_JSON).content("{}")
                .with(xsrf()).with(signedIn(account(Role.INSTRUCTOR))))
                .isError(HttpStatus.UNSUPPORTED_MEDIA_TYPE, MEDIA_TYPE_UNSUPPORTED)
                .hasHeaderContaining(HttpHeaders.ACCEPT, MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Test
    @DisplayName("a file over the 5 MB part limit is 413 through Tomcat")
    void aFileOverThePartLimitIsContentTooLarge() throws Exception {
        Browser browser = instructor();
        browser.send(upload(browser, MULTIPART, HttpRequest.BodyPublishers.ofByteArray(multipartBody(6 * MB))))
                .isError(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE);
    }

    @Test
    @DisplayName("parts that add up past the 10 MB request limit are 413 through Tomcat")
    void partsOverTheRequestLimitAreContentTooLarge() throws Exception {
        Browser browser = instructor();
        // Each part is under the 5 MB part limit; together they are 11 MB. Streamed without a
        // Content-Length, so Tomcat has to read up to the limit to find it, as it does for a chunked
        // upload, and little enough is left unread afterwards that the response arrives intact.
        byte[] body = multipartBody(4 * MB, 4 * MB, 3 * MB);
        browser.send(upload(browser, MULTIPART,
                        HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))))
                .isError(HttpStatus.CONTENT_TOO_LARGE, TOO_LARGE);
    }

    @Test
    @DisplayName("a multipart body that ends inside a part is 400 through Tomcat")
    void aTruncatedMultipartBodyIsABadRequest() throws Exception {
        Browser browser = instructor();
        byte[] body = (partHeader("file") + "and no closing boundary follows")
                .getBytes(StandardCharsets.US_ASCII);
        browser.send(upload(browser, MULTIPART, HttpRequest.BodyPublishers.ofByteArray(body)))
                .isError(HttpStatus.BAD_REQUEST, MALFORMED);
    }

    @Test
    @DisplayName("a multipart request that declares no boundary is 400 through Tomcat")
    void aMultipartRequestWithoutABoundaryIsABadRequest() throws Exception {
        Browser browser = instructor();
        browser.send(upload(browser, MediaType.MULTIPART_FORM_DATA_VALUE,
                        HttpRequest.BodyPublishers.ofByteArray(multipartBody(16))))
                .isError(HttpStatus.BAD_REQUEST, MALFORMED);
    }

    // ── Existing answers, unchanged ──────────────────────────────────────────

    @Test
    @DisplayName("an unparseable JSON body is still 400 with the malformed-request message")
    void anUnparseableBodyIsStillABadRequest() throws Exception {
        perform(post("/api/v1/instructor/courses")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":")
                .with(xsrf()).with(signedIn(account(Role.INSTRUCTOR))))
                .isError(HttpStatus.BAD_REQUEST, MALFORMED);
    }

    @Test
    @DisplayName("a path nothing is mapped to is still 404")
    void anUnmappedPathIsStillNotFound() throws Exception {
        perform(get("/api/v1/no-such-endpoint")
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.NOT_FOUND, "The requested resource was not found");
    }

    // ── What must still be a 500 ─────────────────────────────────────────────

    @Test
    @DisplayName("an unexpected failure is still 500, and says nothing about itself")
    void anUnexpectedFailureIsStillAServerError() throws Exception {
        perform(get("/test-only/server-faults/unexpected")
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED)
                .doesNotEcho("SELECT", "password", "IllegalState");
    }

    @Test
    @DisplayName("a route bound to a path variable its template lacks is still 500")
    void aMappingDefectIsStillAServerError() throws Exception {
        // MissingPathVariableException is a ServletRequestBindingException, the same family as a
        // missing query parameter, but it means the code is wrong rather than the request.
        perform(get("/test-only/server-faults/unbound")
                .with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED);
    }

    @Test
    @DisplayName("an upload the server could not store is still 500, not blamed on the client")
    void anUnusableUploadLocationIsStillAServerError() throws Exception {
        perform(multipart("/test-only/server-faults/upload-location-gone")
                .file(new MockMultipartFile("file", "cover.png", "image/png", new byte[16]))
                .with(xsrf()).with(signedIn(account(Role.STUDENT))))
                .isError(HttpStatus.INTERNAL_SERVER_ERROR, UNEXPECTED)
                .doesNotEcho("tomcat", "/tmp");
    }

    // ── Messages ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("every new message has Arabic text of its own")
    void everyNewMessageIsTranslated() {
        for (String key : List.of(
                "error.request.parameterInvalid",
                "error.request.parameterMissing",
                "error.request.partMissing",
                "error.request.methodNotAllowed",
                "error.request.mediaTypeUnsupported",
                "error.request.notAcceptable",
                "error.request.tooLarge")) {
            Object[] args = {"mode"};
            // A key missing from messages_ar resolves to the English text, so equality is the gap.
            assertThat(messageSource.getMessage(key, args, Locale.of("ar")))
                    .as(key)
                    .isNotEqualTo(messageSource.getMessage(key, args, Locale.ENGLISH));
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User account(Role role) {
        return userRepository.save(User.builder()
                .fullName("Client Error Probe")
                .email(role.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID() + DOMAIN)
                .password(passwordEncoder.encode(PASSWORD))
                .emailVerified(true)
                .role(role)
                .build());
    }

    /**
     * A matching XSRF cookie and header, as the SPA sends them. Not Spring Security's {@code csrf()}
     * post-processor: that one replaces the application's cookie token repository on the shared
     * {@code CsrfFilter} with a session-backed test repository, and the real-port requests in this
     * class, served by the same filter, are then refused as CSRF failures.
     */
    private static RequestPostProcessor xsrf() {
        return request -> {
            String token = UUID.randomUUID().toString();
            request.setCookies(new Cookie("XSRF-TOKEN", token));
            request.addHeader("X-XSRF-TOKEN", token);
            return request;
        };
    }

    private Answer perform(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        var sent = result.getRequest();
        var response = result.getResponse();
        String target = sent.getQueryString() == null
                ? sent.getRequestURI()
                : sent.getRequestURI() + "?" + sent.getQueryString();
        return new Answer(sent.getMethod() + " " + target,
                response.getStatus(), response.getContentAsString(), response::getHeader);
    }

    /** Signs a real instructor in over the real port, once, through the same flow the SPA uses. */
    private Browser instructor() throws Exception {
        if (instructorBrowser == null) {
            User instructor = account(Role.INSTRUCTOR);
            var browser = new Browser(HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .build(), new LinkedHashMap<>(), "http://localhost:" + port);

            browser.send(browser.request("/api/v1/auth/csrf").GET());
            Answer signIn = browser.send(browser.request("/api/v1/auth/login")
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"email":"%s","password":"%s"}
                            """.formatted(instructor.getEmail(), PASSWORD))));
            assertThat(signIn.status()).as("sign-in over the real port: %s", signIn.body()).isEqualTo(200);
            // Signing in may rotate the CSRF token; take the one that now belongs to the session.
            browser.send(browser.request("/api/v1/auth/csrf").GET());
            instructorBrowser = browser;
        }
        return instructorBrowser;
    }

    private static HttpRequest.Builder upload(Browser browser, String contentType, HttpRequest.BodyPublisher body) {
        return browser.request("/api/v1/uploads").header(HttpHeaders.CONTENT_TYPE, contentType).POST(body);
    }

    private static String partHeader(String name) {
        return "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + name + ".png\"\r\n"
                + "Content-Type: image/png\r\n\r\n";
    }

    /** A well-formed multipart body with one zero-filled file part per size, the first named {@code file}. */
    private static byte[] multipartBody(int... partSizes) {
        var body = new ByteArrayOutputStream();
        for (int i = 0; i < partSizes.length; i++) {
            body.writeBytes(partHeader(i == 0 ? "file" : "extra" + i).getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(new byte[partSizes[i]]);
            body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    /** One exchange, from either transport, reduced to what the client can observe. */
    private record Answer(String request, int status, String body, Function<String, String> header) {

        Answer isError(HttpStatus expected, String error) {
            // The exchange is the description, so a failing probe reports what it actually got.
            assertThat(status).as("%s answered %d %s", request, status, body).isEqualTo(expected.value());
            assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("error");
            assertThat(JsonPath.<List<String>>read(body, "$.errors")).as(request).containsExactly(error);
            assertThat(body).doesNotContain("Exception", "java.", "org.springframework", "org.apache");
            return this;
        }

        Answer doesNotEcho(String... fragments) {
            assertThat(body).doesNotContain(fragments);
            return this;
        }

        Answer hasHeaderContaining(String name, String value) {
            assertThat(header.apply(name)).as("%s header on %s", name, request).contains(value);
            return this;
        }
    }

    /**
     * A client holding a real session cookie, echoing the CSRF cookie the way the SPA does. Cookies are
     * kept by hand, as SessionCeilingTest keeps them, so what is sent back is exactly what was set.
     */
    private record Browser(HttpClient client, Map<String, String> cookies, String origin) {

        HttpRequest.Builder request(String rawPathAndQuery) {
            return HttpRequest.newBuilder(URI.create(origin + rawPathAndQuery));
        }

        Answer send(HttpRequest.Builder request) throws IOException, InterruptedException {
            if (!cookies.isEmpty()) {
                request.header(HttpHeaders.COOKIE, cookies.entrySet().stream()
                        .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                        .collect(Collectors.joining("; ")));
            }
            String token = cookie("XSRF-TOKEN");
            if (!token.isEmpty()) {
                request.header("X-XSRF-TOKEN", token);
            }
            HttpRequest built = request.build();
            HttpResponse<String> response = client.send(built, HttpResponse.BodyHandlers.ofString());
            response.headers().allValues(HttpHeaders.SET_COOKIE).forEach(this::remember);
            String target = built.uri().getRawQuery() == null
                    ? built.uri().getRawPath()
                    : built.uri().getRawPath() + "?" + built.uri().getRawQuery();
            return new Answer(built.method() + " " + target, response.statusCode(), response.body(),
                    name -> response.headers().firstValue(name).orElse(null));
        }

        String cookie(String name) {
            return cookies.getOrDefault(name, "");
        }

        private void remember(String setCookie) {
            String pair = setCookie.split(";", 2)[0];
            int equals = pair.indexOf('=');
            String name = pair.substring(0, equals).trim();
            String value = pair.substring(equals + 1).trim();
            if (value.isEmpty() || setCookie.toLowerCase(Locale.ROOT).contains("max-age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }
        }
    }

    /**
     * Failures that are the server's own, reachable on demand. Imported by this class only — a
     * nested class of a test is excluded from component scanning, so no other context sees it.
     */
    @RestController
    @RequestMapping("/test-only/server-faults")
    static class ServerFaults {

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("could not execute statement: SELECT password FROM users");
        }

        /** Binds a variable its own template does not declare: the code is wrong, not the request. */
        @GetMapping("/unbound")
        String unbound(@PathVariable Long id) {
            return "unreachable";
        }

        /**
         * What Spring reports for a well-formed upload when Tomcat's temporary upload directory has
         * been removed from under it — the outage a tmp cleaner causes on a long-running instance.
         */
        @PostMapping("/upload-location-gone")
        String uploadLocationGone() {
            throw new MultipartException("Failed to parse multipart servlet request", new IOException(
                    "The temporary upload location [/tmp/tomcat.8080/work/Tomcat/localhost/ROOT] is not valid"));
        }
    }
}
