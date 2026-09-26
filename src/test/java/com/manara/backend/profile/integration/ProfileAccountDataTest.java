package com.manara.backend.profile.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account screen's data: what the profile reports, what a rename accepts, and the photo.
 *
 * <p>Every photo assertion reads the disk and the row as well as the response. A photo request that
 * answered 400 after replacing the stored file, or 200 while leaving the previous file behind, would
 * look correct from the status code alone.
 */
class ProfileAccountDataTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@profiledata.example";

    /** An upload directory of this class's own; see {@code UploadAuthorizationTest} for why static. */
    static final Path uploadDir = createIsolatedUploadDir();

    private static Path createIsolatedUploadDir() {
        try {
            return Files.createTempDirectory("manara-profile-data-test");
        } catch (IOException e) {
            throw new IllegalStateException("could not create the test upload directory", e);
        }
    }

    @DynamicPropertySource
    static void uploadDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.uploads.dir", uploadDir::toString);
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @AfterEach
    void removeTestAccounts() {
        jdbc.update("DELETE FROM uploads WHERE uploader_user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ── What the profile reports ──────────────────────────────────────────────

    @Test
    @DisplayName("A legacy account reports its password date as unknown, not as its creation date")
    void legacyPasswordDateIsUnknown() throws Exception {
        User legacy = account("legacy", Role.STUDENT);

        mockMvc.perform(get("/api/v1/profile").with(signedIn(legacy)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andExpect(jsonPath("$.data.avatarUrl").value(nullValue()));
    }

    @Test
    @DisplayName("A recorded password date and an unverified address are reported as they are")
    void recordedFactsAreReported() throws Exception {
        User account = account("recorded", Role.INSTRUCTOR);
        jdbc.update("UPDATE users SET password_changed_at = ?, email_verified = false WHERE id = ?",
                LocalDateTime.of(2026, 9, 1, 8, 30), account.getId());

        mockMvc.perform(get("/api/v1/profile").with(signedIn(account)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordChangedAt").value("2026-09-01T08:30:00"))
                .andExpect(jsonPath("$.data.emailVerified").value(false));
    }

    // ── Rename ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A rename is trimmed and answered with the whole updated profile")
    void renameReturnsTheProfile() throws Exception {
        User account = account("rename", Role.STUDENT);

        mockMvc.perform(put("/api/v1/profile").with(csrf()).with(signedIn(account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"   سارة أحمد   "}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("سارة أحمد"))
                .andExpect(jsonPath("$.data.email").value("rename" + DOMAIN))
                .andExpect(jsonPath("$.data.role").value("STUDENT"));

        assertThat(storedName(account)).isEqualTo("سارة أحمد");
    }

    @Test
    @DisplayName("The server refuses a blank or over-long name even when the client does not")
    void invalidNamesAreRefused() throws Exception {
        User account = account("invalid-name", Role.STUDENT);

        for (String name : new String[]{"    ", "ا".repeat(71)}) {
            mockMvc.perform(put("/api/v1/profile").with(csrf()).with(signedIn(account))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fullName\":\"" + name + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(storedName(account)).isEqualTo("Profile Tester");

        // Exactly seventy, after trimming, is allowed.
        String seventy = "ب".repeat(70);
        mockMvc.perform(put("/api/v1/profile").with(csrf()).with(signedIn(account))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\" " + seventy + " \"}"))
                .andExpect(status().isOk());
        assertThat(storedName(account)).isEqualTo(seventy);
    }

    // ── Photo ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A student's photo is re-encoded to a 512 square and named by a single-segment URL")
    void studentPhotoIsStoredAsASquare() throws Exception {
        User student = account("photo", Role.STUDENT);

        String url = uploadPhoto(student, image(800, 600, "png"), "me.png", "image/png");

        assertThat(url).matches("^/uploads/[0-9a-f-]{36}\\.png$");
        BufferedImage stored = ImageIO.read(storedFile(url).toFile());
        assertThat(stored.getWidth()).isEqualTo(512);
        assertThat(stored.getHeight()).isEqualTo(512);
        assertThat(avatarOf(student)).isEqualTo(url);

        mockMvc.perform(get("/api/v1/auth/me").with(signedIn(student)))
                .andExpect(jsonPath("$.data.avatarUrl").value(url));
    }

    @Test
    @DisplayName("Replacing a photo releases the previous file once the new one is in place")
    void replacementReleasesThePreviousFile() throws Exception {
        User instructor = account("replace", Role.INSTRUCTOR);

        String first = uploadPhoto(instructor, image(300, 300, "jpg"), "a.jpg", "image/jpeg");
        String second = uploadPhoto(instructor, image(400, 400, "png"), "b.png", "image/png");

        assertThat(first).endsWith(".jpg");
        assertThat(avatarOf(instructor)).isEqualTo(second);
        assertThat(Files.exists(storedFile(second))).isTrue();
        assertThat(Files.exists(storedFile(first)))
                .as("the replaced photo is no longer referenced by anything and was the caller's")
                .isFalse();
    }

    @Test
    @DisplayName("A refused photo leaves the previous one and the disk exactly as they were")
    void refusedPhotoChangesNothing() throws Exception {
        User student = account("refused", Role.STUDENT);
        String current = uploadPhoto(student, image(200, 200, "png"), "ok.png", "image/png");
        long filesBefore = storedFileCount();

        // Too small, deceptively named bytes, a GIF, and a file over the size limit.
        MockMultipartFile[] refused = {
                new MockMultipartFile("file", "tiny.png", "image/png", image(99, 300, "png")),
                new MockMultipartFile("file", "script.png", "image/png", "<svg onload=alert(1)>".getBytes()),
                new MockMultipartFile("file", "still.gif", "image/gif", image(200, 200, "gif")),
                new MockMultipartFile("file", "huge.png", "image/png", new byte[5 * 1024 * 1024 + 1]),
        };
        for (MockMultipartFile file : refused) {
            mockMvc.perform(multipart("/api/v1/profile/avatar").file(file).with(csrf()).with(signedIn(student)))
                    .andExpect(status().isBadRequest());
        }

        assertThat(avatarOf(student)).isEqualTo(current);
        assertThat(Files.exists(storedFile(current))).isTrue();
        assertThat(storedFileCount()).isEqualTo(filesBefore);
    }

    @Test
    @DisplayName("Removing the photo is idempotent and deletes the caller's file")
    void removalIsIdempotent() throws Exception {
        User student = account("remove", Role.STUDENT);
        String url = uploadPhoto(student, image(200, 200, "png"), "me.png", "image/png");

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(delete("/api/v1/profile/avatar").with(csrf()).with(signedIn(student)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avatarUrl").value(nullValue()));
        }

        assertThat(avatarOf(student)).isNull();
        assertThat(Files.exists(storedFile(url))).isFalse();
    }

    @Test
    @DisplayName("One account's photo change never touches another account")
    void photosAreOwnerScoped() throws Exception {
        User owner = account("owner", Role.STUDENT);
        User other = account("other", Role.STUDENT);
        String ownersPhoto = uploadPhoto(owner, image(200, 200, "png"), "o.png", "image/png");

        uploadPhoto(other, image(200, 200, "png"), "x.png", "image/png");
        mockMvc.perform(delete("/api/v1/profile/avatar").with(csrf()).with(signedIn(other)))
                .andExpect(status().isOk());

        assertThat(avatarOf(owner)).isEqualTo(ownersPhoto);
        assertThat(Files.exists(storedFile(ownersPhoto))).isTrue();
    }

    @Test
    @DisplayName("Opening the photo route did not open the generic upload to students")
    void genericUploadStaysInstructorOnly() throws Exception {
        User student = account("generic", Role.STUDENT);

        mockMvc.perform(multipart("/api/v1/uploads")
                        .file(new MockMultipartFile("file", "c.png", "image/png", image(200, 200, "png")))
                        .with(csrf()).with(signedIn(student)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An anonymous photo upload is refused")
    void anonymousPhotoIsRefused() throws Exception {
        mockMvc.perform(multipart("/api/v1/profile/avatar")
                        .file(new MockMultipartFile("file", "c.png", "image/png", image(200, 200, "png")))
                        .with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private String uploadPhoto(User account, byte[] bytes, String name, String type) throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/profile/avatar")
                        .file(new MockMultipartFile("file", name, type, bytes))
                        .with(csrf()).with(signedIn(account)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(matchesPattern("^/uploads/[^/]+$")))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.data.avatarUrl");
    }

    private User account(String localPart, Role role) {
        return userRepository.save(User.builder()
                .fullName("Profile Tester")
                .email(localPart + DOMAIN)
                .password("irrelevant")
                .role(role)
                .emailVerified(true)
                .build());
    }

    private String storedName(User account) {
        return jdbc.queryForObject("SELECT full_name FROM users WHERE id = ?", String.class, account.getId());
    }

    private String avatarOf(User account) {
        return jdbc.queryForObject("SELECT avatar_url FROM users WHERE id = ?", String.class, account.getId());
    }

    private static Path storedFile(String url) {
        return uploadDir.resolve(url.substring("/uploads/".length()));
    }

    private static long storedFileCount() throws IOException {
        try (Stream<Path> entries = Files.list(uploadDir)) {
            return entries.filter(Files::isRegularFile).count();
        }
    }

    /** A real, decodable image with some content, so a re-encode has pixels to work on. */
    private static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(new Color(0x4E5B92));
        graphics.fillRect(0, 0, width, height / 2);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        byte[] encoded = bytes.toByteArray();
        // Sanity: the fixture itself must decode, or a refusal would prove nothing.
        assertThat(ImageIO.read(new ByteArrayInputStream(encoded))).isNotNull();
        return encoded;
    }
}
