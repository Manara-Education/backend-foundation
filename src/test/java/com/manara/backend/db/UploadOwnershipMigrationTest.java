package com.manara.backend.db;

import com.manara.backend.common.file.UploadOwnershipRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13, and the rows that were already there when it ran.
 *
 * <p>The table this adds exists to answer one question — who uploaded this file — so the only way
 * it can do harm is by answering it wrongly. There is exactly one way for that to happen: inventing
 * an answer for a file uploaded before anybody was recording. Every existing course cover on the
 * platform is such a file, and a plausible-looking back-fill is available (the instructor who owns
 * the course that references the URL), which is precisely the inference the finding is about. It
 * would take the defect — "a reference proves ownership" — and write it into the schema, where it
 * would then authorise deletions.
 *
 * <p>So the migration is asserted to write nothing at all, in two independent ways: by reading the
 * file and confirming it contains no data statement, and by putting a pre-existing course with an
 * upload cover in front of it and confirming that the cover is still there, still referenced, and
 * still owner-unknown. Owner-unknown is the conservative state, and it is the state every legacy
 * asset must be in: undeletable, unbroken, and never guessed at.
 *
 * <p>The rest is the shape of the record. {@code uploader_user_id} being NOT NULL is load-bearing
 * rather than tidy — a nullable owner would let "we never knew" and "owned by nobody" be the same
 * stored value, and one of the two would eventually be read as permission.
 */
class UploadOwnershipMigrationTest extends AbstractPostgresBackedTest {

    private static final Path MIGRATION =
            Path.of("src", "main", "resources", "db", "migration", "V13__upload_ownership.sql");

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired UploadOwnershipRegistry uploadOwnershipRegistry;

    // ── The migration ran ────────────────────────────────────────────────────

    @Test
    @DisplayName("V13 is applied, and the uploads table it creates exists")
    void migrationIsApplied() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM flyway_schema_history
                WHERE version = '13' AND success = true
                """, Integer.class))
                .as("V13 is not recorded as applied")
                .isOne();

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = 'uploads'
                """, Integer.class)).isOne();
    }

    // ── Nothing was guessed, nothing was destroyed ───────────────────────────

    @Test
    @DisplayName("the migration contains no INSERT, UPDATE or DELETE — it cannot invent an owner")
    void theMigrationWritesNoData() throws IOException {
        String sql = statementsOf(Files.readString(MIGRATION, StandardCharsets.UTF_8));

        for (String statement : new String[]{"insert", "update", "delete", "truncate"}) {
            assertThat(Pattern.compile("\\b" + statement + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(sql).find())
                    .as("V13 contains a %s statement; ownership must never be back-filled, and a "
                            + "legacy file must never be removed by a migration", statement.toUpperCase())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a course whose cover predates the record keeps it, and stays owner-unknown")
    void legacyCoversAreUntouchedAndUnowned() {
        transactionTemplate.executeWithoutResult(status -> {
            String storedName = "legacy-" + UUID.randomUUID() + ".png";
            String url = "/uploads/" + storedName;
            long courseId = seedCourse("Legacy cover", url);

            // Still referenced: the migration did not clear the column or delete the course's cover.
            assertThat(jdbc.queryForObject(
                    "SELECT image FROM courses WHERE id = ?", String.class, courseId))
                    .as("a legacy cover must survive the migration untouched")
                    .isEqualTo(url);

            // And still unowned: no row was invented from the fact that a course points at it.
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM uploads WHERE stored_name = ?", Integer.class, storedName))
                    .as("ownership was inferred from a reference — the exact mistake being fixed")
                    .isZero();

            assertThat(uploadOwnershipRegistry.ownerOf(url))
                    .as("owner-unknown is what the retention rule reads as 'never delete'")
                    .isEmpty();

            status.setRollbackOnly();
        });
    }

    // ── The shape of the record ──────────────────────────────────────────────

    @Test
    @DisplayName("the columns are as intended, with the owner NOT NULL")
    void columnsAreAsIntended() {
        assertThat(columnType("id")).isEqualTo("bigint");
        assertThat(columnType("stored_name")).isEqualTo("character varying(255)");
        assertThat(columnType("uploader_user_id")).isEqualTo("bigint");
        assertThat(columnType("content_type")).isEqualTo("character varying(100)");
        assertThat(columnType("size_bytes")).isEqualTo("bigint");
        assertThat(columnType("created_at")).isEqualTo("timestamp(6) without time zone");

        assertThat(nullability("stored_name")).isEqualTo("NO");
        assertThat(nullability("created_at")).isEqualTo("NO");
        assertThat(nullability("uploader_user_id"))
                .as("a nullable owner would make 'we never knew' indistinguishable from a value, "
                        + "and absence is the only honest way to say unknown")
                .isEqualTo("NO");
        assertThat(nullability("content_type")).isEqualTo("YES");
        assertThat(nullability("size_bytes")).isEqualTo("YES");
    }

    @Test
    @DisplayName("one file has one owner: a second row for the same stored name is refused")
    void storedNameIsUnique() {
        transactionTemplate.executeWithoutResult(status -> {
            long userId = seedUser("unique-owner");
            String storedName = UUID.randomUUID() + ".png";

            jdbc.update("INSERT INTO uploads (stored_name, uploader_user_id) VALUES (?, ?)",
                    storedName, userId);

            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO uploads (stored_name, uploader_user_id) VALUES (?, ?)",
                    storedName, seedUser("second-owner")))
                    .as("two rows claiming one file means 'is this yours?' has two answers")
                    .isInstanceOf(DataIntegrityViolationException.class);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the owner must be a real account, and must be present")
    void theOwnerIsAForeignKeyAndRequired() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO uploads (stored_name, uploader_user_id) VALUES (?, ?)",
                    UUID.randomUUID() + ".png", -1L))
                    .isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });

        transactionTemplate.executeWithoutResult(status -> {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO uploads (stored_name, uploader_user_id) VALUES (?, NULL)",
                    UUID.randomUUID() + ".png"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'fk_uploads_uploader'
                """, Integer.class)).isOne();
    }

    @Test
    @DisplayName("created_at defaults, so a row written without one still says when it appeared")
    void createdAtDefaults() {
        transactionTemplate.executeWithoutResult(status -> {
            String storedName = UUID.randomUUID() + ".png";
            jdbc.update("INSERT INTO uploads (stored_name, uploader_user_id) VALUES (?, ?)",
                    storedName, seedUser("defaulted"));

            assertThat(jdbc.queryForObject(
                    "SELECT created_at IS NOT NULL FROM uploads WHERE stored_name = ?",
                    Boolean.class, storedName)).isTrue();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("both lookups the record is read by are indexed")
    void theLookupsAreIndexed() {
        // By uploader: what has this account stored.
        assertThat(indexCount("uploads", "idx_uploads_uploader")).isOne();
        // By stored name: the ownership question itself, served by the unique constraint's index.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint WHERE conname = 'uk_uploads_stored_name'
                """, Integer.class)).isOne();
        // By image: the remaining-reference count, read on the deletion path.
        assertThat(indexCount("courses", "idx_courses_image")).isOne();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** The file with its {@code --} commentary removed, so prose about SQL is not read as SQL. */
    private static String statementsOf(String file) {
        return file.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .reduce("", (a, b) -> a + "\n" + b);
    }

    private String columnType(String column) {
        return jdbc.queryForObject("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                WHERE a.attrelid = 'public.uploads'::regclass AND a.attname = ? AND a.attnum > 0
                """, String.class, column);
    }

    private String nullability(String column) {
        return jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'uploads' AND column_name = ?
                """, String.class, column);
    }

    private Integer indexCount(String table, String index) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = ? AND indexname = ?
                """, Integer.class, table, index);
    }

    private long seedUser(String label) {
        return jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, label,
                ("v13-" + label + "-" + UUID.randomUUID() + "@x.test").toLowerCase(Locale.ROOT));
    }

    /** A course as it existed before this migration: an upload cover and no ownership record. */
    private long seedCourse(String title, String image) {
        long userId = seedUser(title.replace(' ', '-'));
        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id, image)
                VALUES (?, 'seeded', 'PUBLISHED', 'FLAT', 'FREE', 0, now(), ?, ?) RETURNING id
                """, Long.class, title, instructorId, image);
    }
}
