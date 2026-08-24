package com.manara.backend.db;

import com.manara.backend.email.service.EmailService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the whole application against a real PostgreSQL and a real Redis.
 *
 * <p>Everything below this class is about a promise the database keeps, not one the application
 * makes: that Flyway migrates an empty database on startup, and that PostgreSQL itself refuses a
 * second account whose address differs only in case. Neither can be demonstrated against H2, an
 * embedded dialect or a mocked repository — those would all report success while the real database
 * was wrong, which is precisely the failure being closed.
 *
 * <p>The containers are started once for the JVM and shared by every subclass, rather than per
 * class: Spring caches the application context across test classes with the same configuration, so
 * one PostgreSQL, one Redis and one context serve all of them. Ryuk removes the containers when
 * the JVM exits, so there is nothing to stop explicitly.
 *
 * <p>The image tags match docker-compose.yml deliberately. A test that passes on a different major
 * version of PostgreSQL than the one production runs is not evidence about production.
 */
@SpringBootTest
public abstract class AbstractPostgresBackedTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.5")
            .withDatabaseName("manara_db")
            .withUsername("postgres")
            .withPassword("password");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * Registration sends an OTP inside the caller's transaction, so a provider that rejects the
     * message rolls the new account back. Stubbed here because these tests are about the database,
     * not about Resend — and because reaching a real provider from a test suite would be wrong
     * regardless of what it proved.
     */
    @MockitoBean
    protected EmailService emailService;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
