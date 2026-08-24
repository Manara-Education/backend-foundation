package com.manara.backend.db;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves Flyway actually migrates a database, rather than merely being configured to.
 *
 * <p>The container this runs against is created empty. So every table the application needs exists
 * here for exactly one reason: a migration created it. And the context could only have started at
 * all because {@code spring.jpa.hibernate.ddl-auto=validate} found the entity model matching that
 * schema — which pins the ordering too. Hibernate's validation is the thing that used to fail with
 * "missing table [banner_dismissals]" when Flyway was inert; it passing here means the migrations
 * ran, and that they ran <em>before</em> anything depended on the schema they build.
 */
class FlywayMigrationTest extends AbstractPostgresBackedTest {

    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("a Flyway bean exists — the single thing whose absence caused the silent failure")
    void flywayBeanIsPresent() {
        // With flyway-core alone and no starter this injection point simply could not be
        // satisfied, and the context would fail here instead of starting happily and migrating
        // nothing.
        assertThat(flyway).isNotNull();
    }

    @Test
    @DisplayName("flyway_schema_history records every migration in the repository as applied")
    void everyMigrationIsRecordedAsApplied() throws IOException {
        List<String> onDisk = versionsOnDisk();

        List<String> applied = jdbc.query(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE version IS NOT NULL AND success = true ORDER BY installed_rank",
                (rs, row) -> rs.getString("version"));

        assertThat(applied)
                .as("every migration under db/migration must appear in flyway_schema_history")
                .containsAll(onDisk);
        assertThat(applied).as("V4 is the migration that makes stored addresses canonical")
                .contains("4");
    }

    @Test
    @DisplayName("no migration is recorded as failed")
    void noMigrationFailed() {
        Integer failures = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);

        assertThat(failures).isZero();
    }

    @Test
    @DisplayName("the schema is at the latest version, with nothing pending")
    void schemaIsAtTheLatestVersion() throws IOException {
        MigrationInfo current = flyway.info().current();

        assertThat(current).as("Flyway reports no current version — nothing was applied").isNotNull();
        assertThat(current.getVersion().getVersion())
                .isEqualTo(versionsOnDisk().getLast());
        assertThat(flyway.info().pending())
                .as("pending migrations after startup: %s",
                        Arrays.toString(flyway.info().pending()))
                .isEmpty();
    }

    @Test
    @DisplayName("running Flyway again applies nothing — a restart finds the schema up to date")
    void restartIsANoOp() {
        // The second half of the fresh-database check. Migrating an already-migrated database must
        // change nothing; if this executed anything, migrations would be re-running on every boot.
        var result = flyway.migrate();

        assertThat(result.migrationsExecuted)
                .as("a second migrate() executed %s migration(s) against an up-to-date schema",
                        result.migrationsExecuted)
                .isZero();
        assertThat(result.success).isTrue();
    }

    @Test
    @DisplayName("the tables the entity model needs were created by migrations, not by Hibernate")
    void schemaWasBuiltByMigrations() {
        // banner_dismissals is named on purpose: it is the table whose absence surfaced the
        // inert-Flyway bug, as a Hibernate validation failure on an empty database.
        for (String table : List.of("users", "otps", "banner_dismissals", "flyway_schema_history")) {
            assertThat(tableExists(table)).as("table %s is missing", table).isTrue();
        }
    }

    @Test
    @DisplayName("the objects V2 and V4 add are present in the migrated schema")
    void emailGuaranteesArePresent() {
        Integer index = jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' AND tablename = 'users' "
                        + "AND indexname = 'uk_users_email_lower'",
                Integer.class);
        assertThat(index).as("V2's case-insensitive unique index is missing").isEqualTo(1);

        Integer check = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint "
                        + "WHERE conname = 'ck_users_email_canonical' AND contype = 'c'",
                Integer.class);
        assertThat(check).as("V4's canonical-form check constraint is missing").isEqualTo(1);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Integer.class, table);
        return count != null && count == 1;
    }

    /** Migration versions as the repository declares them, in ascending order. */
    private static List<String> versionsOnDisk() throws IOException {
        try (var files = Files.list(MIGRATION_DIR)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                    .toList();
        }
    }
}
