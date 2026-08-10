package com.manara.backend.common.exception;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.email.exception.EmailDeliveryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<@NonNull ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(messageService.get("error.unexpected")));
    }
}
