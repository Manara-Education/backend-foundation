package com.manara.backend.common.file;

import com.manara.backend.db.AbstractPostgresBackedTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The retention rule itself, one condition at a time.
 *
 * <p>{@code CoverReplacementOwnershipTest} drives this through a real course edit, which is what
 * proves the finding is closed. This drives the decision directly, because the conditions that make
 * it safe are easier to get wrong individually than together — particularly the two that look like
 * defensive noise until they are not: a URL that is not one of ours, and a URL that is shaped like
 * one of ours but is not a plain stored name.
 *
 * <p>Files are real, and every assertion is about whether the bytes are still there. That is the
 * whole subject.
 */
class UploadRetentionServiceTest extends AbstractPostgresBackedTest {

    private static final byte[] BYTES = "stored-bytes".getBytes();

    @Autowired UploadRetentionService uploadRetentionService;
    @Autowired UploadOwnershipRegistry uploadOwnershipRegistry;
    @Autowired UploadedFileRepository uploadedFileRepository;
    @Autowired UploadProperties uploadProperties;
    @Autowired JdbcTemplate jdbc;

    private final List<Path> written = new ArrayList<>();

    @AfterEach
    void removeFilesThisTestCreated() {
        written.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Not a test failure.
            }
        });
        written.clear();
    }

    @Test
    @DisplayName("an owner releasing their own unreferenced file deletes it, and forgets the record")
    void theOwnerOfAnUnreferencedFileMayReleaseIt() {
        long owner = seedUser();
        String url = storeOwnedBy(owner);

        uploadRetentionService.release(url, owner);

        assertThat(exists(url)).isFalse();
        assertThat(uploadedFileRepository.findByStoredName(storedNameOf(url))).isEmpty();
    }

    @Test
    @DisplayName("somebody who did not upload the file cannot cause its deletion")
    void aNonOwnerIsRefused() {
        long owner = seedUser();
        long other = seedUser();
        String url = storeOwnedBy(owner);

        uploadRetentionService.release(url, other);

        assertThat(exists(url)).isTrue();
        assertThat(uploadOwnershipRegistry.ownerOf(url)).isPresent();
    }

    @Test
    @DisplayName("a file with no ownership record is kept, whoever asks")
    void anUnrecordedFileIsKept() {
        String url = store();

        uploadRetentionService.release(url, seedUser());

        assertThat(exists(url))
                .as("absence of a record is 'we do not know', which is never permission")
                .isTrue();
    }

    @Test
    @DisplayName("a course still showing the file keeps it, even for its uploader")
    void aRemainingReferenceKeepsTheFile() {
        long owner = seedUser();
        String url = storeOwnedBy(owner);
        seedCourseWithCover(owner, url);

        uploadRetentionService.release(url, owner);

        assertThat(exists(url)).isTrue();
        assertThat(uploadedFileRepository.findByStoredName(storedNameOf(url)))
                .as("the record outlives the release attempt because the file does")
                .isPresent();
    }

    @Test
    @DisplayName("an external cover URL is not this mechanism's business")
    void anExternalUrlIsIgnored() {
        assertThatCode(() -> uploadRetentionService
                .release("https://cdn.example.com/cover.png", seedUser()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a URL shaped like an upload but not a plain stored name is refused outright")
    void aUrlThatIsNotAPlainStoredNameIsRefused() {
        long owner = seedUser();
        String url = storeOwnedBy(owner);
        String storedName = storedNameOf(url);

        // Same file, same owner, same request — reached by a path rather than by a name. Refusing
        // instead of normalising is deliberate: sanitising is how a hostile string gets a second
        // chance at being accepted.
        for (String dressedUp : List.of(
                "/uploads/./" + storedName,
                "/uploads/../uploads/" + storedName,
                "/uploads/sub/" + storedName)) {
            assertThat(UploadOwnershipRegistry.storedNameOf(dressedUp)).isEmpty();
            uploadRetentionService.release(dressedUp, owner);
        }

        assertThat(exists(url)).isTrue();
    }

    @Test
    @DisplayName("a null URL or a null requester decides nothing")
    void nothingIsDecidedWithoutBothHalves() {
        assertThatCode(() -> {
            uploadRetentionService.releaseWhenCommitted(null, 1L);
            uploadRetentionService.releaseWhenCommitted("/uploads/whatever.png", null);
        }).doesNotThrowAnyException();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String storeOwnedBy(long uploaderUserId) {
        String url = store();
        uploadOwnershipRegistry.record(url, uploaderUserId, "image/png", (long) BYTES.length);
        return url;
    }

    private String store() {
        String storedName = UUID.randomUUID() + ".png";
        Path directory = uploadDirectory();
        try {
            Files.createDirectories(directory);
            Path path = directory.resolve(storedName);
            Files.write(path, BYTES);
            written.add(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return "/uploads/" + storedName;
    }

    private Path uploadDirectory() {
        return Path.of(uploadProperties.dir()).toAbsolutePath().normalize();
    }

    private boolean exists(String url) {
        return Files.exists(uploadDirectory().resolve(storedNameOf(url)));
    }

    private static String storedNameOf(String url) {
        return url.substring("/uploads/".length());
    }

    private long seedUser() {
        return jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Retention', ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, "retention-" + UUID.randomUUID() + "@x.test");
    }

    private void seedCourseWithCover(long userId, String cover) {
        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);
        jdbc.update("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id, image)
                VALUES ('Referencing', 'seeded', 'DRAFT', 'FLAT', 'FREE', 0, now(), ?, ?)
                """, instructorId, cover);
    }
}
