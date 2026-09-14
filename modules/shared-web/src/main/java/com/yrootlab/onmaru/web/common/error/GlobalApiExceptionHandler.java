package com.yrootlab.onmaru.web.common.error;

import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyConflictException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyInvalidException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyMissingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public final class GlobalApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> methodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        var fieldErrors = new LinkedHashMap<String, String>();
        for (var error : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        return error(ApiErrorCode.VALIDATION_ERROR, request, Map.of("fieldErrors", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiErrorResponse> constraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        var violations = new LinkedHashMap<String, String>();
        for (var violation : exception.getConstraintViolations()) {
            violations.put(violation.getPropertyPath().toString(), violation.getMessage());
        }
        return error(ApiErrorCode.VALIDATION_ERROR, request, Map.of("fieldErrors", violations));
    }

    @ExceptionHandler(CursorInvalidException.class)
    ResponseEntity<ApiErrorResponse> cursorInvalid(HttpServletRequest request) {
        return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
    }

    @ExceptionHandler(CursorExpiredException.class)
    ResponseEntity<ApiErrorResponse> cursorExpired(HttpServletRequest request) {
        return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> idempotencyConflict(HttpServletRequest request) {
        return error(ApiErrorCode.IDEMPOTENCY_CONFLICT, request, Map.of());
    }

    @ExceptionHandler(IdempotencyKeyMissingException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyMissing(HttpServletRequest request) {
        return validationError(request, "is required");
    }

    @ExceptionHandler(IdempotencyKeyInvalidException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyInvalid(HttpServletRequest request) {
        return validationError(request, "must be a UUID");
    }

    private ResponseEntity<ApiErrorResponse> validationError(HttpServletRequest request, String message) {
        return error(ApiErrorCode.VALIDATION_ERROR, request, Map.of(
                "fieldErrors", Map.of(IdempotencyKey.HEADER, message)));
    }

    private ResponseEntity<ApiErrorResponse> error(
            ApiErrorCode code,
            HttpServletRequest request,
            Map<String, Object> details) {
        return ResponseEntity
                .status(code.status())
                .body(ApiErrorResponse.of(code, requestId(request), details));
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }
}
