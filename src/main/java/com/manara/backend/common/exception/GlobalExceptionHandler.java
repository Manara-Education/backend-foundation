package com.manara.backend.common.exception;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.exception.EmailDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

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
