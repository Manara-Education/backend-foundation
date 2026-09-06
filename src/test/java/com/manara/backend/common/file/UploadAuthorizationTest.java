package com.manara.backend.common.file;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who may write into the directory the web server hands out.
 *
 * <p>{@code POST /api/v1/uploads} was covered only by the filter chain's terminal
 * {@code anyRequest().authenticated()} rule, so any signed-in account could use it — students
 * included, who have no upload feature anywhere in the product. What they could reach is a
 * directory served publicly at {@code /uploads/**} on the same disk as the application: general
 * file hosting, available to anyone who could register.
 *
 * <p>Every assertion below counts files as well as reading status codes. A 403 that has already
 * written the bytes is not a refusal, and status codes alone would not tell the two apart.
 *
 * <p>The last case calls the service directly rather than over HTTP. The URL rule and the service
 * check are two separate defences, and a test that only drives the endpoint cannot tell whether
 * both are present or only the first.
 */
class UploadAuthorizationTest extends AbstractPostgresBackedTest {

    /**
     * A directory of this test's own, so the files it counts are unambiguously the ones it caused
     * and the repository's own {@code uploads/} is untouched.
     *
     * <p>Created in a static initialiser rather than with {@code @TempDir}, deliberately.
     * {@code FileUploadService} resolves {@code app.uploads.dir} in its <em>constructor</em>, so the
     * value has to exist by the time the application context is built; a {@code @TempDir} static
     * field is populated by a JUnit callback whose ordering against Spring's context loading is not
     * something this test should be relying on. A static initialiser runs before either.
     */
    static final Path uploadDir = createIsolatedUploadDir();

    private static Path createIsolatedUploadDir() {
        try {
            return Files.createTempDirectory("manara-upload-auth-test");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the test upload directory", e);
        }
    }

    /**
     * Redirects storage into that directory.
     *
     * <p>Declaring a second {@code @DynamicPropertySource} alongside the one inherited from
     * {@link AbstractPostgresBackedTest} also gives this class its own context cache key, so the
     * override cannot be lost to a context another test class built first.
     */
    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.uploads.dir", uploadDir::toString);
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private FileUploadService fileUploadService;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("An anonymous upload is refused, and writes nothing")
    void anonymousUploadIsRefused() throws Exception {
        long before = storedFileCount();

        mockMvc.perform(multipart("/api/v1/uploads").file(pngPart()).with(csrf()))
                .andExpect(status().isUnauthorized());

        assertThat(storedFileCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("A student's upload is refused, and writes nothing")
    void studentUploadIsRefused() throws Exception {
        long before = storedFileCount();

        mockMvc.perform(multipart("/api/v1/uploads").file(pngPart())
                        .with(csrf())
                        .with(user(account(Role.STUDENT))))
                .andExpect(status().isForbidden());

        assertThat(storedFileCount())
                .as("a refused upload must not have reached the disk")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("An admin's upload is refused too — this endpoint belongs to instructors")
    void adminUploadIsRefused() throws Exception {
        long before = storedFileCount();

        mockMvc.perform(multipart("/api/v1/uploads").file(pngPart())
                        .with(csrf())
                        .with(user(account(Role.ADMIN))))
                .andExpect(status().isForbidden());

        assertThat(storedFileCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("An instructor's upload still works")
    void instructorUploadIsStored() throws Exception {
        long before = storedFileCount();

        mockMvc.perform(multipart("/api/v1/uploads").file(pngPart())
                        .with(csrf())
                        .with(user(account(Role.INSTRUCTOR))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.url").value(
                        org.hamcrest.Matchers.matchesPattern("^/uploads/[0-9a-f-]{36}\\.png$")));

        assertThat(storedFileCount())
                .as("exactly one file for one accepted upload")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("The service refuses a non-instructor even when called directly")
    void serviceRefusesNonInstructorWithoutTheUrlRule() throws Exception {
        long before = storedFileCount();

        assertThatThrownBy(() -> fileUploadService.storeFile(pngPart(), account(Role.STUDENT)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.onlyInstructor");

        assertThatThrownBy(() -> fileUploadService.storeFile(pngPart(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.file.onlyInstructor");

        assertThat(storedFileCount()).isEqualTo(before);
    }

    // ------------------------------------------------------------ helpers

    /** An in-memory account. Never persisted: authorization here is decided by the role alone. */
    private static User account(Role role) {
        return User.builder()
                .id(1L)
                .fullName("Upload Tester")
                .email(role.name().toLowerCase() + "@uploadauth.example")
                .password("irrelevant")
                .role(role)
                .emailVerified(true)
                .build();
    }

    /** A real 1x1 PNG — the service decodes the bytes, so a fake payload would fail validation. */
    private static MockMultipartFile pngPart() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", bytes);
        return new MockMultipartFile("file", "cover.png", "image/png", bytes.toByteArray());
    }

    private static long storedFileCount() throws IOException {
        if (!Files.isDirectory(uploadDir)) {
            return 0;
        }
        try (Stream<Path> entries = Files.list(uploadDir)) {
            return entries.filter(Files::isRegularFile).count();
        }
    }
}
