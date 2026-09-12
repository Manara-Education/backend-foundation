package com.manara.backend.common.exception;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.terms.exception.TermsUnavailableException;
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
import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

    /**
     * A business condition the domain refused. {@code 409} when the caller's copy of the world is
     * out of date, {@code 400} otherwise, and both carry the condition's {@link ErrorCode} when it
     * has one so a client can branch on the condition rather than on a translated sentence.
     *
     * <p>Ordered before {@link #handleBusiness}: Spring picks the most specific handler, but stating
     * it here keeps the pair readable as one decision.
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleConflict(ConflictException ex) {
        String message = messageService.get(ex.getMessageCode(), ex.getArgs());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(message, ex.getErrorCode()));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleBusiness(BusinessException ex) {
        String message = messageService.get(ex.getMessageCode(), ex.getArgs());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(message, ex.getErrorCode()));
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
     * The application cannot name the Terms and Conditions version in force, so it refuses to take
     * consent rather than take it against nothing.
     *
     * <p>{@code 503}, following {@link #handleEmailDelivery} — the same shape of condition: the
     * request was fine, a dependency of it was not, and retrying later is the caller's correct move.
     * The alternative is what makes this worth a handler of its own: falling through to the generic
     * 500 would tell the client the server broke, and answering anything in the 2xx range would mean
     * an account created with no record of what its owner agreed to.
     *
     * <p>Logged at ERROR because it is a deployment fault, not a caller's mistake: registration is
     * down for everyone until a build that can name a current version is running.
     */
    @ExceptionHandler(TermsUnavailableException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleTermsUnavailable(TermsUnavailableException ex) {
        log.error("Terms and Conditions version unavailable; registration refused", ex);
        String message = messageService.get(ex.getMessageCode(), ex.getArgs());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(message, ErrorCode.TERMS_UNAVAILABLE));
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
     *
     * <p>This is a backstop, not a business-rule channel. Every constraint an ordinary instructor
     * flow used to trip — a lesson position already taken, a subscription plan a learner still
     * holds — is now decided in the domain and answered with its own {@link ErrorCode}, because
     * "the request conflicts with data that already exists" told the instructor nothing they could
     * act on and the client nothing it could branch on. Anything that still reaches here is a bug
     * or a genuine race, and the generic message is the honest answer to both.
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

    // --- The request is at fault -------------------------------------------
    //
    // Each of these is raised by Spring MVC before, or instead of, running a controller, because
    // the request cannot be matched or bound. Until they had handlers of their own the catch-all
    // below answered them, so a non-numeric id or an unknown enum value reached the client as a 500
    // and the log as an ERROR with a stack trace (pentest, 2026-09-10). None of them quotes the
    // submitted value back.
    //
    // MissingPathVariableException is deliberately not among them. It shares a parent with a
    // missing query parameter, but it means a controller binds a variable its own template does not
    // declare: the code is wrong, not the request, and it stays a 500.

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.debug("Request value for '{}' could not be converted", ex.getName());
        return badRequest("error.request.parameterInvalid", ex.getName());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException ex) {
        return badRequest("error.request.parameterMissing", ex.getParameterName());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return badRequest("error.request.partMissing", ex.getRequestPartName());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex) {
        var response = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            response.allow(ex.getSupportedHttpMethods().toArray(HttpMethod[]::new));
        }
        return response.body(ApiResponse.error(messageService.get("error.request.methodNotAllowed")));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex) {
        HttpHeaders headers = new HttpHeaders();
        if (!ex.getSupportedMediaTypes().isEmpty()) {
            headers.setAccept(ex.getSupportedMediaTypes());
        }
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .headers(headers)
                .body(ApiResponse.error(messageService.get("error.request.mediaTypeUnsupported")));
    }

    /**
     * The body's type is set rather than negotiated: the request has just said it accepts nothing
     * this API produces, so negotiating the error against it fails the same way and sends an empty
     * 406.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(messageService.get("error.request.notAcceptable")));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                .body(ApiResponse.error(messageService.get("error.request.tooLarge")));
    }

    /**
     * A multipart body the container could not parse is the client's fault only when the parser
     * refused the body itself -- no boundary, a part that never ends -- which Tomcat reports as a
     * FileUploadException somewhere in the cause chain. Any other multipart failure is the server
     * failing to handle a well-formed upload, such as its temporary upload directory having been
     * removed, and is still answered as one.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleMultipart(MultipartException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof FileUploadException) {
                log.debug("Malformed multipart request", ex);
                return badRequest("error.request.malformed");
            }
        }
        return handleGeneric(ex);
    }

    private ResponseEntity<@NonNull ApiResponse<Void>> badRequest(String messageCode, Object... args) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error(messageService.get(messageCode, args)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(messageService.get("error.unexpected")));
    }
}
