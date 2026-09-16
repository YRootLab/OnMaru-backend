package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationInputInvalidException;
import com.yrootlab.onmaru.journey.exploration.ExplorationNotFoundException;
import com.yrootlab.onmaru.journey.exploration.ExplorationInputRejectedException;
import com.yrootlab.onmaru.journey.exploration.ExplorationTurnConflictException;
import com.yrootlab.onmaru.journey.exploration.ExplorationVersionConflictException;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
final class ExplorationExceptionHandler {

    @ExceptionHandler(ExplorationInputInvalidException.class)
    ResponseEntity<ApiErrorResponse> invalidInput(
            ExplorationInputInvalidException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.BAD_REQUEST,
                ApiErrorCode.VALIDATION_ERROR.name(),
                "Exploration input is invalid.",
                request,
                Map.of("field", exception.field()));
    }

    @ExceptionHandler(ExplorationInputRejectedException.class)
    ResponseEntity<ApiErrorResponse> rejectedInput(
            ExplorationInputRejectedException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.UNPROCESSABLE_ENTITY,
                exception.code(),
                "Exploration input was rejected by intake policy.",
                request,
                Map.of());
    }

    @ExceptionHandler(ExplorationAuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> authenticationRequired(HttpServletRequest request) {
        return error(
                HttpStatus.UNAUTHORIZED,
                "AUTH_REQUIRED",
                "Authentication or guest credential is required.",
                request,
                Map.of());
    }

    @ExceptionHandler(ExplorationNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return error(
                ApiErrorCode.NOT_FOUND.status(),
                ApiErrorCode.NOT_FOUND.name(),
                ApiErrorCode.NOT_FOUND.message(),
                request,
                Map.of());
    }

    @ExceptionHandler(ExplorationVersionConflictException.class)
    ResponseEntity<ApiErrorResponse> versionConflict(
            ExplorationVersionConflictException exception,
            HttpServletRequest request) {
        return error(
                HttpStatus.CONFLICT,
                "VERSION_CONFLICT",
                "Exploration state version is stale.",
                request,
                Map.of("currentVersion", exception.currentVersion()));
    }

    @ExceptionHandler(ExplorationTurnConflictException.class)
    ResponseEntity<ApiErrorResponse> turnConflict(HttpServletRequest request) {
        return error(
                HttpStatus.CONFLICT,
                ApiErrorCode.IDEMPOTENCY_CONFLICT.name(),
                ApiErrorCode.IDEMPOTENCY_CONFLICT.message(),
                request,
                Map.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, Object> details) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, message, requestId(request), details));
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        return UUID.randomUUID().toString();
    }
}
