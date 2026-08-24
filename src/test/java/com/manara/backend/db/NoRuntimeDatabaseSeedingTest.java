package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The database is populated by Flyway, never by application code.
 *
 * <p>This is the test that keeps {@code DataSeeder} deleted. That class was a
 * {@link CommandLineRunner} that inserted two loginable accounts — with a password written in
 * the file — plus a sample catalogue, on every startup that was not the production profile. It
 * had shipped without that profile guard, and the accounts reached the production database.
 *
 * <p>Deleting it fixed that instance. This fixes the class of it: any future startup hook that
 * writes to the database has to get past these two assertions first. Every row the application
 * needs is created by a Flyway migration under {@code src/main/resources/db/migration}, which is
 * versioned, reviewed and identical in every environment — not by code that runs on boot and
 * behaves differently depending on which profile happens to be active.
 *
 * <p>Both checks are here because neither alone is enough. The classpath scan catches a class
 * that implements the interface; the source scan catches an {@code @Bean} method that returns
 * one, which has no type of its own to find.
 */
class NoRuntimeDatabaseSeedingTest {

    private static final String APPLICATION_PACKAGE = "com.manara.backend";
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    @DisplayName("no class in the application implements CommandLineRunner or ApplicationRunner")
    void noStartupRunnerClassesExist() {
        // useDefaultFilters=false so this finds runners by TYPE rather than by stereotype
        // annotation: a plain `implements CommandLineRunner` registered from a @Bean method
        // carries no @Component and would otherwise slip through.
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(CommandLineRunner.class));
        scanner.addIncludeFilter(new AssignableTypeFilter(ApplicationRunner.class));

        var runners = scanner.findCandidateComponents(APPLICATION_PACKAGE).stream()
                .map(definition -> definition.getBeanClassName())
                .toList();

        assertThat(runners)
                .as("startup runners under %s — the database belongs to Flyway, not to the "
                        + "application; initial data goes in db/migration", APPLICATION_PACKAGE)
                .isEmpty();
    }

    @Test
    @DisplayName("no source file registers a startup runner or reintroduces the seeder")
    void noSourceFileMentionsAStartupRunner() throws IOException {
        // Catches what the type scan cannot: `@Bean CommandLineRunner seed(...)`, which has no
        // class of its own. Also catches the old names coming back under a new one.
        Set<String> forbidden = Set.of(
                "CommandLineRunner", "ApplicationRunner",
                "DataSeeder", "DatabaseSeeder", "DataInitializer");

        assertThat(SOURCE_ROOT)
                .as("main sources must be readable from the module directory for this scan to mean anything")
                .isDirectory();

        record Offence(Path file, String token) {}
        List<Offence> offences;
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT)) {
            offences = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        String body = read(path);
                        return forbidden.stream()
                                .filter(body::contains)
                                .map(token -> new Offence(path, token));
                    })
                    .toList();
        }

        assertThat(offences)
                .as("runtime database seeding must not come back — schema and data are Flyway's")
                .isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }
}
