package com.manara.backend.common.exception;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.service.MessageService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * What the client is told when the database, not the application, refuses a write.
 *
 * <p>{@code AuthService#register} checks {@code existsByEmail} before inserting, but the check and
 * the insert are two statements. Two registrations for the same address, arriving together, can
 * both pass the check; one of them then loses at {@code uk_users_email_lower}. The database has to
 * refuse it — that is the whole reason the index exists — so the only question is what the loser
 * sees.
 *
 * <p>It must see the ordinary duplicate-account response. Before this handler existed the
 * violation fell through to the catch-all and became a 500, which tells the caller their address is
 * unusable for reasons unknown, and invites a retry that will fail identically.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataIntegrityViolationHandlingTest {

    private static final String DUPLICATE = "Email is already registered";
    private static final String CONFLICT = "The request conflicts with data that already exists";

    @Mock
    private MessageService messageService;

    @InjectMocks
    private GlobalExceptionHandler handler;

    private void stubMessages() {
        given(messageService.get("auth.email.duplicate")).willReturn(DUPLICATE);
        given(messageService.get("error.conflict")).willReturn(CONFLICT);
    }

    @Test
    @DisplayName("losing the race at the case-insensitive index looks like an ordinary duplicate")
    void caseInsensitiveIndexViolationBecomesTheDuplicateResponse() {
        stubMessages();

        var response = handler.handleDataIntegrityViolation(violation(
                "ERROR: duplicate key value violates unique constraint \"uk_users_email_lower\"",
                "uk_users_email_lower"));

        // Identical to what the pre-check returns, so a race is indistinguishable from simply
        // registering an address that already exists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorsOf(response)).containsExactly(DUPLICATE);
    }

    @Test
    @DisplayName("the older UNIQUE(email) constraint maps to the same response")
    void plainUniqueConstraintViolationBecomesTheDuplicateResponse() {
        stubMessages();

        // An exact duplicate trips this one first — it was created before the functional index, so
        // PostgreSQL reaches it first. Both have to map to the same thing.
        var response = handler.handleDataIntegrityViolation(violation(
                "ERROR: duplicate key value violates unique constraint "
                        + "\"uk6dotkott2kjsp8vw4d0m25fb7\"",
                "uk6dotkott2kjsp8vw4d0m25fb7"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorsOf(response)).containsExactly(DUPLICATE);
    }

    @Test
    @DisplayName("an unrelated integrity violation is a conflict, not a duplicate email")
    void otherViolationsGetAGenericConflict() {
        stubMessages();

        var response = handler.handleDataIntegrityViolation(violation(
                "ERROR: insert or update on table \"enrollments\" violates foreign key constraint "
                        + "\"fk_enrollments_course\"",
                "fk_enrollments_course"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(errorsOf(response)).containsExactly(CONFLICT);
    }

    @Test
    @DisplayName("no PostgreSQL detail is ever put in the response body")
    void theDriversMessageNeverReachesTheClient() {
        stubMessages();

        var response = handler.handleDataIntegrityViolation(violation(
                "ERROR: duplicate key value violates unique constraint \"uk_users_email_lower\" "
                        + "Detail: Key (lower(email))=(ali@x.com) already exists.",
                "uk_users_email_lower"));

        // The constraint name and the offending address are useful in the log and nowhere else:
        // one names internal schema, the other confirms to an anonymous caller that a particular
        // person holds an account here.
        assertThat(errorsOf(response))
                .noneMatch(error -> error.contains("uk_users_email_lower"))
                .noneMatch(error -> error.contains("ali@x.com"))
                .noneMatch(error -> error.contains("duplicate key"));
    }

    private static DataIntegrityViolationException violation(String message, String constraint) {
        var sqlException = new SQLException(message, "23505");
        var cause = new ConstraintViolationException(
                message, sqlException, ConstraintViolationException.ConstraintKind.UNIQUE, constraint);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    private static java.util.List<String> errorsOf(ResponseEntity<ApiResponse<Void>> response) {
        ApiResponse<Void> body = response.getBody();
        assertThat(body).isNotNull();
        return body.getErrors();
    }
}
