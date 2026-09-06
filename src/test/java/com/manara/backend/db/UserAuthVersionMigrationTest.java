package com.manara.backend.db;

import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V13, against rows that were already there.
 *
 * <p>{@link FlywayMigrationTest} proves the migration runs, but it runs against an empty container,
 * so it says nothing about a database with accounts in it. What V13 has to get right for an
 * existing database is that <em>every account keeps working</em>: {@code auth_version} decides
 * whether a session is honoured, so a row that came out of the migration with no value, or with a
 * value the application did not put there, is an account whose sessions can never match. A NULL
 * would be worse still — the comparison would silently never hold and the account would be locked
 * out of every session it ever opened.
 *
 * <p>The other half is that the entity and the column still agree. Production runs
 * {@code ddl-auto=validate}, so a column the migration creates with a type Hibernate does not
 * expect stops the application from starting at all — which means the context this test is running
 * in is itself part of the assertion, and is stated as such below rather than left implicit.
 */
class UserAuthVersionMigrationTest extends AbstractPostgresBackedTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired UserRepository userRepository;
    @Autowired Environment environment;

    @Test
    @DisplayName("the column exists on users, as a NOT NULL bigint defaulting to 0")
    void theColumnIsShapedTheWayTheEntityExpects() {
        assertThat(jdbc.queryForObject("""
                SELECT data_type FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'auth_version'
                """, String.class)).isEqualTo("bigint");

        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'auth_version'
                """, String.class))
                .as("a NULL epoch never equals a session's stamp, so the account could never sign in")
                .isEqualTo("NO");

        assertThat(jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'auth_version'
                """, String.class)).contains("0");
    }

    @Test
    @DisplayName("an account row that predates the column comes out at epoch 0, not null")
    void existingAccountsAreAtEpochZero() {
        transactionTemplate.executeWithoutResult(status -> {
            // Inserted without naming the column, exactly as an instance running the previous
            // build would -- the default is what has to be right, not the application.
            long userId = seedUser("v13-legacy");

            assertThat(jdbc.queryForObject(
                    "SELECT auth_version FROM users WHERE id = ?", Long.class, userId))
                    .isZero();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("the column refuses NULL")
    void theColumnRefusesNull() {
        transactionTemplate.executeWithoutResult(status -> {
            long userId = seedUser("v13-null");

            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE users SET auth_version = NULL WHERE id = ?", userId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            status.setRollbackOnly();
        });
    }

    /**
     * The read and the write the request pipeline and the credential-change paths actually use,
     * against the real column rather than a mock of the repository.
     */
    @Test
    @DisplayName("the projection reads the row's epoch, and the bump moves it by exactly one")
    void theProjectionAndTheBumpAgreeWithTheColumn() {
        transactionTemplate.executeWithoutResult(status -> {
            long userId = seedUser("v13-bump");

            assertThat(userRepository.findAuthStateById(userId))
                    .get()
                    .extracting(UserRepository.AuthState::getAuthVersion)
                    .isEqualTo(0L);

            assertThat(userRepository.bumpAuthVersion(userId))
                    .as("one row, the account's own")
                    .isOne();

            assertThat(jdbc.queryForObject(
                    "SELECT auth_version FROM users WHERE id = ?", Long.class, userId))
                    .isEqualTo(1L);

            userRepository.bumpAuthVersion(userId);

            assertThat(userRepository.findAuthStateById(userId))
                    .get()
                    .extracting(UserRepository.AuthState::getAuthVersion)
                    .as("each credential change is its own epoch; they do not collapse")
                    .isEqualTo(2L);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("bumping an account that does not exist changes nothing and reports so")
    void bumpingAMissingAccountIsANoOp() {
        transactionTemplate.executeWithoutResult(status -> {
            assertThat(userRepository.bumpAuthVersion(-1L)).isZero();
            status.setRollbackOnly();
        });
    }

    /**
     * Hibernate checks the entity model against the schema at startup and never alters it, so a
     * context that refreshed is a schema the mapping agrees with. Stated explicitly because the
     * evidence is otherwise invisible: this whole class silently proves nothing if the profile has
     * quietly moved off {@code validate}.
     */
    @Test
    @DisplayName("the context started under ddl-auto=validate, so the mapping matches the column")
    void theMappingIsValidatedAgainstTheSchema() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("with any other value Hibernate would reshape the schema instead of checking it, "
                        + "and this class would be asserting against a table it created itself")
                .isEqualTo("validate");
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedUser(String tag) {
        // Deliberately without naming `auth_version`: this is the insert an instance running the
        // previous build makes, and its rows have to come out at epoch 0.
        return jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'STUDENT', now()) RETURNING id
                """, Long.class, tag, tag + "@v13-migration.test");
    }
}
