package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the Flyway setup itself, and it needs no database to do it.
 *
 * <p>The bug this exists to prevent had no symptom. The application had {@code flyway-core} on the
 * classpath and nothing else; Spring Boot 4 moved {@code FlywayAutoConfiguration} out of
 * {@code spring-boot-autoconfigure} and into the per-technology {@code spring-boot-flyway} module,
 * which {@code flyway-core} does not bring with it. So no {@link org.flywaydb.core.Flyway} bean was
 * ever created. The application started cleanly, logged not one line about Flyway, ran none of the
 * migrations, and left the schema at whatever it happened to be. Nothing failed — which is exactly
 * what made it dangerous.
 *
 * <p>{@code FlywayMigrationTest} proves migrations really run, but it needs Docker. This one is
 * pure classpath and file inspection, so it runs everywhere, always, and fails the build the moment
 * the setup is wrong — before anyone has to notice that a log line stopped appearing.
 */
class FlywayConfigurationTest {

    private static final String AUTO_CONFIGURATION_CLASS =
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration";
    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern MIGRATION_NAME = Pattern.compile("^V(\\d+)__[A-Za-z0-9_]+\\.sql$");

    // ---------------------------------------------------------------- dependencies

    @Test
    @DisplayName("the build depends on Spring Boot's Flyway STARTER, not on flyway-core alone")
    void pomDeclaresTheFlywayStarter() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom)
                .as("pom.xml must declare spring-boot-starter-flyway — under Spring Boot 4 a bare "
                        + "flyway-core dependency carries no autoconfiguration, so Flyway silently "
                        + "never runs")
                .contains("<artifactId>spring-boot-starter-flyway</artifactId>");

        assertThat(pom)
                .as("flyway-core must not be declared directly; the starter brings the right "
                        + "version of it, and declaring it separately is how the broken setup "
                        + "looked in the first place")
                .doesNotContain("<artifactId>flyway-core</artifactId>");
    }

    @Test
    @DisplayName("FlywayAutoConfiguration is on the classpath at its Spring Boot 4 coordinates")
    void autoConfigurationClassIsPresent() {
        assertThatClassExists(AUTO_CONFIGURATION_CLASS);
    }

    @Test
    @DisplayName("PostgreSQL support is on the classpath — flyway-core alone does not recognise it")
    void postgresqlSupportIsPresent() {
        // Since Flyway 10 each database lives in its own artifact. Without this one Flyway starts
        // and then refuses the connection with "Unsupported Database: PostgreSQL".
        assertThatClassExists("org.flywaydb.database.postgresql.PostgreSQLDatabaseType");
    }

    @Test
    @DisplayName("FlywayAutoConfiguration is registered, so Spring will actually apply it")
    void autoConfigurationIsRegistered() throws IOException {
        List<String> registered = new ArrayList<>();
        Enumeration<URL> imports = getClass().getClassLoader().getResources(IMPORTS_RESOURCE);
        while (imports.hasMoreElements()) {
            try (InputStream in = imports.nextElement().openStream()) {
                new String(in.readAllBytes(), StandardCharsets.UTF_8)
                        .lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(registered::add);
            }
        }

        // Being on the classpath is not the same as being applied: an autoconfiguration Spring
        // never imports is as inert as one that is not there at all.
        assertThat(registered)
                .as("no AutoConfiguration.imports on the classpath registers Flyway")
                .contains(AUTO_CONFIGURATION_CLASS);
    }

    // ---------------------------------------------------------------- configuration

    @Test
    @DisplayName("Flyway is enabled, pointed at db/migration, and loud about a missing location")
    void flywayIsConfiguredToFailLoudly() throws IOException {
        Properties config = applicationProperties();

        assertThat(config.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(config.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");

        // Without this, a location that does not resolve is not an error: Flyway reports
        // "0 migrations" and startup proceeds — indistinguishable from Flyway not running at all,
        // which is the failure mode this whole class exists to prevent.
        assertThat(config.getProperty("spring.flyway.fail-on-missing-locations"))
                .as("spring.flyway.fail-on-missing-locations must be true so a bad location aborts "
                        + "startup instead of quietly migrating nothing")
                .isEqualTo("true");

        // An edited migration is a mistake to fix, not to tolerate.
        assertThat(config.getProperty("spring.flyway.validate-on-migrate")).isEqualTo("true");

        // `flyway clean` drops every object in the schema.
        assertThat(config.getProperty("spring.flyway.clean-disabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("Hibernate never generates schema — Flyway owns it in every profile")
    void hibernateNeverOwnsTheSchema() throws IOException {
        for (String profile : List.of("application.properties", "application-prod.properties")) {
            String ddlAuto = load(profile).getProperty("spring.jpa.hibernate.ddl-auto");

            assertThat(ddlAuto)
                    .as("%s must pin spring.jpa.hibernate.ddl-auto; leaving it unset lets a future "
                            + "default decide whether Hibernate may alter production tables", profile)
                    .isNotNull();
            assertThat(ddlAuto)
                    .as("%s sets ddl-auto=%s — anything but validate/none hands schema ownership "
                            + "back to Hibernate and makes the migrations decorative", profile, ddlAuto)
                    .isIn("validate", "none");
        }
    }

    // ---------------------------------------------------------------- migrations

    @Test
    @DisplayName("the configured location really contains migrations, and they are on the classpath")
    void migrationsAreDiscoverable() throws IOException {
        Resource[] onClasspath = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql");

        assertThat(onClasspath)
                .as("no migration is reachable at classpath:db/migration — Flyway would find "
                        + "nothing to run, and with fail-on-missing-locations the application would "
                        + "not start")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every migration is named the way Flyway requires, with a unique version")
    void migrationsAreVersionedCorrectly() throws IOException {
        List<String> names;
        try (var files = Files.list(MIGRATION_DIR)) {
            names = files.map(path -> path.getFileName().toString()).sorted().toList();
        }

        assertThat(names).as("db/migration is empty").isNotEmpty();

        List<Integer> versions = new ArrayList<>();
        for (String name : names) {
            Matcher matcher = MIGRATION_NAME.matcher(name);
            assertThat(matcher.matches())
                    .as("'%s' does not match V<version>__<description>.sql, so Flyway ignores it "
                            + "and the change it contains never reaches any database", name)
                    .isTrue();
            versions.add(Integer.parseInt(matcher.group(1)));
        }

        // A repeated version makes Flyway fail at startup; a gap usually means a migration was
        // deleted after being applied somewhere, which validate-on-migrate then reports as a
        // missing applied migration.
        assertThat(versions).doesNotHaveDuplicates();
        List<Integer> expected = new ArrayList<>();
        for (int version = 1; version <= versions.size(); version++) {
            expected.add(version);
        }
        assertThat(versions.stream().sorted(Comparator.naturalOrder()).toList())
                .as("migration versions must run 1..n without gaps")
                .isEqualTo(expected);
    }

    // ---------------------------------------------------------------- helpers

    private static void assertThatClassExists(String className) {
        assertThat(catchClassNotFound(className))
                .as("%s is not on the classpath", className)
                .isNull();
    }

    private static Throwable catchClassNotFound(String className) {
        try {
            Class.forName(className, false, FlywayConfigurationTest.class.getClassLoader());
            return null;
        } catch (ClassNotFoundException e) {
            return e;
        }
    }

    private static Properties applicationProperties() throws IOException {
        return load("application.properties");
    }

    private static Properties load(String name) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = FlywayConfigurationTest.class.getClassLoader().getResourceAsStream(name)) {
            assertThat(in).as("%s is not on the classpath", name).isNotNull();
            properties.load(in);
        }
        return properties;
    }
}
