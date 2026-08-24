package com.manara.backend.user.repository;

import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The repository's own contract, without a database: {@code findByEmail} and {@code existsByEmail}
 * canonicalise before they query.
 *
 * <p>{@code UserEmailUniquenessTest} proves the same thing against real PostgreSQL, but it needs
 * Docker and takes the best part of a minute. This runs in milliseconds and fails the moment
 * someone deletes the {@code EmailAddress.canonical(...)} call from a default method — which would
 * otherwise show up only as users being unable to sign in.
 *
 * <p>{@code CALLS_REAL_METHODS} makes the mock execute the interface's {@code default} bodies while
 * leaving the derived queries stubbable, so what is under test is exactly the code Spring Data does
 * not generate.
 */
class UserRepositoryCanonicalisationTest {

    private static final String CANONICAL = "ali@x.com";

    private final UserRepository repository = mock(UserRepository.class, CALLS_REAL_METHODS);

    private final User account = User.builder()
            .id(7L)
            .fullName("Ali")
            .email(CANONICAL)
            .role(Role.STUDENT)
            .build();

    @ParameterizedTest(name = "findByEmail(\"{0}\") queries for ali@x.com")
    @ValueSource(strings = {"ali@x.com", "Ali@x.com", "ALI@X.COM", "  Ali@x.com  ", "\tali@X.com\n"})
    @DisplayName("the address is canonicalised before it reaches the query")
    void findByEmailCanonicalisesFirst(String raw) {
        given(repository.findOneByEmail(CANONICAL)).willReturn(Optional.of(account));

        assertThat(repository.findByEmail(raw)).contains(account);

        // The important half: the query ran against the canonical form, not the raw one. Stored
        // addresses are canonical by construction, so anything else would match nothing.
        verify(repository).findOneByEmail(CANONICAL);
    }

    @ParameterizedTest(name = "existsByEmail(\"{0}\") queries for ali@x.com")
    @ValueSource(strings = {"Ali@x.com", "ALI@X.COM", "  ali@x.com  "})
    @DisplayName("the duplicate check canonicalises before it queries")
    void existsByEmailCanonicalisesFirst(String raw) {
        given(repository.existsOneByEmail(CANONICAL)).willReturn(true);

        assertThat(repository.existsByEmail(raw)).isTrue();

        verify(repository).existsOneByEmail(CANONICAL);
    }

    @Test
    @DisplayName("a null address does not blow up on the way to the query")
    void toleratesNull() {
        assertThat(repository.findByEmail(null)).isEmpty();
    }
}
