package com.manara.backend.course.integration;

import com.manara.backend.banner.model.Banner;
import com.manara.backend.banner.repository.BannerRepository;
import com.manara.backend.common.file.UploadOwnershipRegistry;
import com.manara.backend.common.file.UploadProperties;
import com.manara.backend.common.file.UploadedFileRepository;
import com.manara.backend.common.json.Patch;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.echoOf;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.flatCourse;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.lesson;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Replacing a cover deletes a file only when the file is yours and nothing else is using it.
 *
 * <p>The behaviour this closes: {@code updateCourse} deleted whatever URL the course's {@code image}
 * column held, on no evidence beyond the payload naming a different one. Since the column is written
 * from an unvalidated client string and {@code /uploads/**} is served publicly, an instructor could
 * set their own course's cover to somebody else's upload — a URL anyone can read off a published
 * course page — save again with a cover of their own, and the second save destroyed a file they had
 * never uploaded. Every ownership check passed on the way through, correctly: all of them were
 * about the course, and none of them was about the file.
 *
 * <p>Every assertion here is about bytes on a disk, because that is what was being destroyed. A
 * service that decided correctly and unlinked anyway would pass a test that only read return values.
 *
 * <p>These tests write into the configured upload directory rather than a temporary one of their
 * own, deliberately: pointing {@code app.uploads.dir} somewhere else would fork a second Spring
 * context for the sake of a directory. Each file is uniquely named and removed afterwards.
 */
class CoverReplacementOwnershipTest extends AbstractCourseAuthoringTest {

    /** Not a real image. Nothing on the retention path decodes it — only the upload path does. */
    private static final byte[] BYTES = "stored-bytes".getBytes();

    @Autowired UploadProperties uploadProperties;
    @Autowired UploadOwnershipRegistry uploadOwnershipRegistry;
    @Autowired UploadedFileRepository uploadedFileRepository;
    @Autowired BannerRepository bannerRepository;
    @Autowired TransactionTemplate transactionTemplate;

    private final List<Path> written = new ArrayList<>();

    @AfterEach
    void removeFilesThisTestCreated() {
        written.forEach(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // A file a test could not clean up is not a test failure.
            }
        });
        written.clear();
    }

    // ── The finding ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("attaching another instructor's upload and then replacing it does not delete it")
    void anotherInstructorsUploadSurvivesBeingAttachedAndReplaced() {
        User otherInstructor = newInstructorUser();
        String theirFile = uploadedBy(otherInstructor);

        // A course this instructor legitimately owns, pointed at a cover they did not upload. The
        // URL is public, so nothing here required any access they did not already have.
        var course = createCourseWith(theirFile);
        String ownFile = uploadedBy(instructorUser);

        var updated = replaceCover(course, ownFile);

        assertThat(exists(theirFile))
                .as("the other instructor's file was deleted by an edit to somebody else's course")
                .isTrue();
        assertThat(uploadOwnershipRegistry.ownerOf(theirFile))
                .as("and its ownership record is intact, so it is still theirs")
                .isPresent();

        // The edit itself was never in question and still works.
        assertThat(updated.getImage()).isEqualTo(ownFile);
        assertThat(reload(course.getId()).getImage()).isEqualTo(ownFile);
    }

    @Test
    @DisplayName("an upload with no ownership record — everything from before the record — is kept")
    void legacyUploadsAreNeverDeleted() {
        String legacy = uploadedByNobody();
        var course = createCourseWith(legacy);

        replaceCover(course, uploadedBy(instructorUser));

        assertThat(exists(legacy))
                .as("an owner cannot be inferred for a legacy file, so it must never be destroyed")
                .isTrue();
    }

    // ── What still has to work ───────────────────────────────────────────────

    @Test
    @DisplayName("an instructor replacing their own unreferenced cover still cleans it up")
    void ownUnreferencedCoverIsReleased() {
        String first = uploadedBy(instructorUser);
        var course = createCourseWith(first);
        String second = uploadedBy(instructorUser);

        replaceCover(course, second);

        assertThat(exists(first)).as("the uploader's own, now unreferenced, file").isFalse();
        assertThat(exists(second)).as("the new cover").isTrue();
        assertThat(uploadedFileRepository.findByStoredName(storedNameOf(first)))
                .as("the record describes bytes that are gone and is dropped with them")
                .isEmpty();
    }

    @Test
    @DisplayName("a published course with enrolled students can still have its cover replaced")
    void publishedEnrolledCourseCoverReplacementIsUnaffected() {
        String first = uploadedBy(instructorUser);
        var course = createCourseWith(first, CourseStatus.PUBLISHED);

        User studentUser = newStudentUser();
        var enrollment = enroll(studentUser, course.getId());
        courseExistedSince(course.getId(), LocalDateTime.now().minusMonths(1));
        enrolledAt(enrollment.getId(), LocalDateTime.now().minusWeeks(2));

        String second = uploadedBy(instructorUser);
        var updated = replaceCover(course, second);

        assertThat(updated.getImage()).isEqualTo(second);
        assertThat(updated.getStatus()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(exists(second)).isTrue();
        assertThat(exists(first)).as("the owner's own retired cover is still cleaned up").isFalse();

        // The learner keeps the course and is shown the new cover.
        var details = detailsFor(studentUser, course.getId());
        assertThat(details.getCourse().getImage()).isEqualTo(second);
    }

    // ── References other things hold ─────────────────────────────────────────

    @Test
    @DisplayName("a file another course still shows survives its uploader replacing it")
    void aFileAnotherCourseReferencesIsKept() {
        String shared = uploadedBy(instructorUser);
        var course = createCourseWith(shared);
        createCourseWith(shared);

        replaceCover(course, uploadedBy(instructorUser));

        assertThat(exists(shared))
                .as("one reference going away does not make a file unreferenced")
                .isTrue();
    }

    @Test
    @DisplayName("a file a banner still shows survives a course cover replacement")
    void aFileABannerReferencesIsKept() {
        String shared = uploadedBy(instructorUser);
        var course = createCourseWith(shared);
        bannerRepository.saveAndFlush(Banner.builder()
                .instructor(instructorProfile)
                .internalName("Promo " + UUID.randomUUID())
                .title("Promotion")
                .imageUrl(shared)
                .timezone("Africa/Cairo")
                .priority(1)
                .build());

        replaceCover(course, uploadedBy(instructorUser));

        assertThat(exists(shared))
                .as("banners hold upload references too, and never delete anything themselves")
                .isTrue();
    }

    // ── Ordering ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a transaction that rolls back after the cover changed leaves the file on disk")
    void aRolledBackEditDeletesNothing() {
        String first = uploadedBy(instructorUser);
        var course = createCourseWith(first);
        String second = uploadedBy(instructorUser);

        // The edit runs inside a transaction that then fails. Deletion is irreversible and the
        // database change is not, so anything undone by the rollback must not have destroyed a file.
        transactionTemplate.executeWithoutResult(status -> {
            replaceCover(course, second);
            status.setRollbackOnly();
        });

        assertThat(exists(first))
                .as("the cover the rolled-back edit replaced is still the course's cover")
                .isTrue();
        assertThat(reload(course.getId()).getImage())
                .as("the rollback really did undo the edit")
                .isEqualTo(first);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A file on disk with an ownership record — what a real upload by this account leaves behind. */
    private String uploadedBy(User uploader) {
        String url = store();
        uploadOwnershipRegistry.record(url, uploader.getId(), "image/png", (long) BYTES.length);
        return url;
    }

    /** A file on disk with no record: everything uploaded before the record existed. */
    private String uploadedByNobody() {
        return store();
    }

    private String store() {
        String storedName = UUID.randomUUID() + ".png";
        Path directory = Path.of(uploadProperties.dir()).toAbsolutePath().normalize();
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

    private boolean exists(String url) {
        return Files.exists(Path.of(uploadProperties.dir()).toAbsolutePath().normalize()
                .resolve(storedNameOf(url)));
    }

    private static String storedNameOf(String url) {
        return url.substring("/uploads/".length());
    }

    private InstructorCourseResponse createCourseWith(String cover) {
        return createCourseWith(cover, CourseStatus.DRAFT);
    }

    private InstructorCourseResponse createCourseWith(String cover, CourseStatus status) {
        var request = flatCourse("Cover " + UUID.randomUUID(), status, lesson("L1"));
        request.setImage(Patch.of(cover));
        return courseService.createCourse(instructorUser, request);
    }

    /** The editor's own payload, with a different cover in it — an ordinary save. */
    private InstructorCourseResponse replaceCover(InstructorCourseResponse course, String cover) {
        var request = echoOf(course);
        request.setImage(Patch.of(cover));
        return courseService.updateCourse(instructorUser, course.getId(), request);
    }
}
