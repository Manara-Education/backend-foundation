package com.manara.backend.user.repository;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One address, one account — asserted against the two authorities that have to agree on it.
 *
 * <p>The application layer is checked through {@link UserRepository}, and the database layer is
 * checked by going around the application entirely and inserting with raw SQL. That second half is
 * the point: {@code existsByEmail} and the insert that follows it are two statements, so two
 * concurrent registrations can both find nothing and both proceed. Only the database can actually
 * refuse the second one, so only a test that bypasses the application can show that it does.
 */
class UserEmailUniquenessTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@uniqueness.example";
    private static final String CANONICAL = "ali" + DOMAIN;
    private static final String PASSWORD_HASH = "$2a$10$notarealhashbutlongenoughtostore";
    private static final String INSERT_USER = """
            INSERT INTO users (full_name, email, password, email_verified,
                               requires_password_reset, role, created_at)
            VALUES (?, ?, ?, false, false, 'STUDENT', now())
            """;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void removeTestAccounts() {
        jdbc.update("DELETE FROM otps WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM students WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM instructors WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ------------------------------------------------------------ application lookups

    @ParameterizedTest(name = "findByEmail(\"{0}\") returns the account stored as ali@uniqueness.example")
    @ValueSource(strings = {
            "ali@uniqueness.example",
            "Ali@uniqueness.example",
            "ALI@UNIQUENESS.EXAMPLE",
            "aLi@UnIqUeNeSs.ExAmPlE",
            "  Ali@uniqueness.example  ",
    })
    @DisplayName("every casing and padding of an address resolves to the one account")
    void caseVariantsResolveToTheSameAccount(String variant) {
        User saved = userRepository.save(account(CANONICAL));

        assertThat(userRepository.findByEmail(variant))
                .as("looking up %s found no account", variant)
                .isPresent()
                .get()
                .extracting(User::getId)
                .isEqualTo(saved.getId());
    }

    @ParameterizedTest(name = "existsByEmail(\"{0}\") is true")
    @ValueSource(strings = {
            "Ali@uniqueness.example",
            "ALI@UNIQUENESS.EXAMPLE",
            "  ali@UNIQUENESS.example ",
    })
    @DisplayName("the duplicate check sees a case variant as the account it already is")
    void duplicateCheckIsCaseInsensitive(String variant) {
        userRepository.save(account(CANONICAL));

        assertThat(userRepository.existsByEmail(variant)).isTrue();
    }

    @Test
    @DisplayName("a different address is still a different account")
    void doesNotOverreach() {
        userRepository.save(account(CANONICAL));

        assertThat(userRepository.findByEmail("ali2" + DOMAIN)).isEmpty();
        assertThat(userRepository.existsByEmail("ali2" + DOMAIN)).isFalse();
    }

    // ------------------------------------------------------------ what actually reaches the column

    @Test
    @DisplayName("the entity stores the canonical form, whatever it was handed")
    void storesTheCanonicalForm() {
        User user = account(CANONICAL);
        user.setEmail("   ALI@Uniqueness.Example  ");
        userRepository.save(user);

        String stored = jdbc.queryForObject(
                "SELECT email FROM users WHERE lower(email) = ?", String.class, CANONICAL);

        assertThat(stored)
                .as("the column must hold the canonical address, not the raw one")
                .isEqualTo(CANONICAL);
    }

    // ------------------------------------------------------------ the database's own guarantee

    @Test
    @DisplayName("PostgreSQL refuses a second account whose address differs only in case")
    void databaseRejectsCaseVariantDuplicate() {
        insertDirectly(CANONICAL);

        // Straight past the service, the mapper and the repository — the way a race between two
        // concurrent registrations reaches the table. It is the CHECK constraint that answers
        // first: a shouted address is not merely a duplicate, it is not a storable value at all,
        // so PostgreSQL rejects it before the unique index is ever consulted.
        assertThatThrownBy(() -> insertDirectly("ALI" + DOMAIN))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the unique index — not just the check constraint — is what enforces uniqueness")
    void uniqueIndexItselfIsCaseInsensitive() {
        insertDirectly(CANONICAL);

        // The previous test proves a case variant is refused, but not by which object. That
        // matters: if only ck_users_email_canonical were doing the work, dropping it would leave
        // the table wide open, and uk_users_email_lower would be decoration.
        //
        // So this drops the check constraint inside a transaction, attempts the insert, and rolls
        // back. PostgreSQL's DDL is transactional, so the constraint is restored whether the
        // assertion passes or fails, and nothing outside this method can observe it missing.
        Exception thrown = insertWithCheckConstraintTemporarilyDropped("ALI" + DOMAIN);

        assertThat(thrown)
                .as("with the check constraint out of the way, the unique index must still refuse "
                        + "a differently-cased duplicate")
                .isNotNull()
                .hasMessageContaining("uk_users_email_lower");

        assertThat(checkConstraintCount())
                .as("the check constraint must be back after the rollback")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("PostgreSQL refuses an exact duplicate too")
    void databaseRejectsExactDuplicate() {
        insertDirectly(CANONICAL);

        assertThatThrownBy(() -> insertDirectly(CANONICAL))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("PostgreSQL refuses an address that is not stored in canonical form")
    void databaseRejectsNonCanonicalStorage() {
        // V4's check constraint. Without it, a hand-written fix or an import script could put
        // 'Ali@…' back in the column — a row that every lookup would then miss.
        assertThatThrownBy(() -> insertDirectly("Ali" + DOMAIN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_email_canonical");

        assertThatThrownBy(() -> insertDirectly(" ali" + DOMAIN))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_email_canonical");
    }

    @Test
    @DisplayName("a genuinely different address is accepted")
    void databaseAcceptsDistinctAddresses() {
        insertDirectly(CANONICAL);

        assertThatCode(() -> insertDirectly("ali2" + DOMAIN)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ helpers

    private static User account(String email) {
        return User.builder()
                .fullName("Ali Test")
                .email(email)
                .password(PASSWORD_HASH)
                .role(Role.STUDENT)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Integer checkConstraintCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_users_email_canonical'",
                Integer.class);
    }

    /**
     * Attempts the insert in a transaction with {@code ck_users_email_canonical} dropped, then
     * rolls the whole thing back. Returns whatever the insert threw, or {@code null}.
     *
     * <p>Every statement runs on the one connection the callback is handed, and that is not a
     * detail. Dropping a constraint takes an ACCESS EXCLUSIVE lock on {@code users}; an insert
     * issued through the {@code JdbcTemplate} would borrow a <em>second</em> connection from the
     * pool and block on that lock until the test timed out.
     */
    private Exception insertWithCheckConstraintTemporarilyDropped(String email) {
        return jdbc.execute((ConnectionCallback<Exception>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement ddl = connection.createStatement()) {
                ddl.execute("ALTER TABLE users DROP CONSTRAINT ck_users_email_canonical");

                try (PreparedStatement insert = connection.prepareStatement(INSERT_USER)) {
                    insert.setString(1, "Ali Test");
                    insert.setString(2, email);
                    insert.setString(3, PASSWORD_HASH);
                    insert.executeUpdate();
                    return null;
                } catch (SQLException e) {
                    return e;
                }
            } finally {
                // Undoes the DROP as well as anything the insert managed to write.
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /** Inserts a row with no application code in the path at all. */
    private void insertDirectly(String email) {
        jdbc.update(INSERT_USER, "Ali Test", email, PASSWORD_HASH);
    }
}
