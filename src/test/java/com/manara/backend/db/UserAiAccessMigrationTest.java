package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17: {@code users.ai_access} exists, is NOT NULL, and grants nobody anything.
 *
 * <p>The context starting at all is part of the evidence: it runs under {@code ddl-auto=validate},
 * so the {@code User} mapping and the column agree.
 */
class UserAiAccessMigrationTest extends AbstractPostgresBackedTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("V17 is applied, and the column is a NOT NULL boolean defaulting to false")
    void theColumnIsShapedAsIntended() {
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '17'", Boolean.class))
                .isTrue();

        assertThat(jdbc.queryForObject(column("data_type"), String.class)).isEqualTo("boolean");
        assertThat(jdbc.queryForObject(column("is_nullable"), String.class)).isEqualTo("NO");
        assertThat(jdbc.queryForObject(column("column_default"), String.class)).isEqualTo("false");
    }

    @Test
    @DisplayName("a row inserted without naming the column has no access")
    void rowsAreCreatedWithoutAccess() {
        transactionTemplate.executeWithoutResult(status -> {
            Boolean access = jdbc.queryForObject("""
                    INSERT INTO users (full_name, email, password, email_verified,
                                       requires_password_reset, role, created_at)
                    VALUES ('legacy', 'v17-legacy@v17-migration.test', 'x', true, false,
                            'STUDENT', now())
                    RETURNING ai_access
                    """, Boolean.class);

            assertThat(access).isFalse();
            status.setRollbackOnly();
        });
    }

    private static String column(String attribute) {
        return "SELECT " + attribute + """
                 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'ai_access'
                """;
    }
}
