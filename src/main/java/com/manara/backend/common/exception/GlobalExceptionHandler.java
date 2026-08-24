package com.manara.backend.common.exception;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.exception.EmailDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    /**
     * The database objects that make two accounts with the same address impossible.
     *
     * <p>{@code uk6dotkott2kjsp8vw4d0m25fb7} is the plain {@code UNIQUE (email)} Hibernate
     * generated before Flyway owned the schema — an opaque name, but a real constraint that is
     * still on the table. {@code uk_users_email_lower} is the functional unique index added in V2,
     * and it is the one a case-variant duplicate trips.
     */
    private static final Set<String> EMAIL_UNIQUENESS_CONSTRAINTS = Set.of(
            "uk_users_email_lower",
            "uk6dotkott2kjsp8vw4d0m25fb7");

    private final MessageService messageService;

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
        String message = messageService.get(ex.getMessageCode(), ex.getArgs());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(message));
    }

    /**
     * A request for a path that does not exist is a 404, not a server error.
     *
     * <p>Without this handler {@link NoResourceFoundException} falls through to the catch-all
     * below, so every request for a missing static resource returned <strong>500</strong> and
     * logged a full stack trace at ERROR. That is wrong twice over: it tells the caller the
     * server broke when it did not, and it lets anyone fill the logs — and the disk — by
     * requesting nonexistent paths in a loop. Observed while confirming Swagger is disabled in
     * production: {@code GET /v3/api-docs} correctly found nothing, and reported 500.
     *
     * <p>Logged at DEBUG: a 404 is routine and says nothing about the server's health.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        log.debug("No resource found: {}", ex.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error(messageService.get("error.notFound")));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleBusiness(BusinessException ex) {
        String message = messageService.get(ex.getMessageCode(), ex.getArgs());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(messageService.get("auth.credentials.invalid")));
    }

    @ExceptionHandler(EmailDeliveryException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleEmailDelivery(EmailDeliveryException ex) {
        // The cause carries provider detail for the logs; the client only ever sees a generic,
        // provider-independent message.
        log.error("Email delivery failed", ex);
        String message = messageService.get(ex.getMessageCode(), ex.getArgs());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(message));
    }

    /**
     * The last line of defence on uniqueness, and the thing that keeps PostgreSQL out of the API.
     *
     * <p>{@code AuthService#register} checks for an existing account first, but that check and the
     * insert are two statements: two concurrent registrations for the same address can both pass
     * it, and one of them then loses at the index. Removing the database constraint to avoid that
     * would be the wrong repair — the constraint is the only thing that actually guarantees
     * uniqueness. So the loser is translated here into exactly the response the pre-check
     * produces, and the race becomes invisible to the client instead of becoming a 500 carrying a
     * PostgreSQL error string.
     *
     * <p>Any other integrity violation is a genuine conflict the caller may be able to resolve;
     * it gets a 409 and a generic message. The driver's own text is logged, never returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleDataIntegrityViolation(
            DataIntegrityViolationException ex) {
        log.warn("Database rejected a write: {}",
                NestedExceptionUtils.getMostSpecificCause(ex).getMessage());

        if (violatesEmailUniqueness(ex)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(messageService.get("auth.email.duplicate")));
        }
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(messageService.get("error.conflict")));
    }

    /**
     * Whether this violation came from one of the email uniqueness objects.
     *
     * <p>Read from the driver's message rather than from a typed accessor: the exception arrives
     * wrapped by Spring, and which layer exposes a constraint name depends on the persistence
     * provider. The message always names the index PostgreSQL refused on.
     */
    private static boolean violatesEmailUniqueness(DataIntegrityViolationException ex) {
        String detail = NestedExceptionUtils.getMostSpecificCause(ex).getMessage();
        if (detail == null) {
            return false;
        }
        String haystack = detail.toLowerCase(Locale.ROOT);
        return EMAIL_UNIQUENESS_CONSTRAINTS.stream().anyMatch(haystack::contains);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(errors));
    }

    /**
     * An unreadable body is the client's mistake, not a server fault — an unparseable enum such as
     * {@code "structure": "chapters"} used to fall through to the generic 500 handler. The parser's
     * own message is logged but never returned: it exposes internal type names.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        log.debug("Malformed request body", ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(messageService.get("error.request.malformed")));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(messageService.get("error.unexpected")));
    }
}
