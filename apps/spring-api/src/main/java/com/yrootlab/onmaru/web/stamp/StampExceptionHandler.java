package com.yrootlab.onmaru.web.stamp;

import com.yrootlab.onmaru.stamp.CheckInInputInvalidException;
import com.yrootlab.onmaru.stamp.CheckInPlaceNotFoundException;
import com.yrootlab.onmaru.stamp.CheckInRateLimitedException;
import com.yrootlab.onmaru.stamp.LocationAccuracyTooLowException;
import com.yrootlab.onmaru.stamp.OutsideCheckInRadiusException;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyInvalidException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyMissingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = StampController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class StampExceptionHandler {

    @ExceptionHandler(IdempotencyKeyMissingException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyMissing(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_MISSING",
                "Idempotency-Key header is required", request, Map.of());
    }

    @ExceptionHandler(IdempotencyKeyInvalidException.class)
    ResponseEntity<ApiErrorResponse> idempotencyKeyInvalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
                "Idempotency-Key header must be a UUID", request, Map.of());
    }

    @ExceptionHandler(CheckInInputInvalidException.class)
    ResponseEntity<ApiErrorResponse> invalid(CheckInInputInvalidException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed",
                request, Map.of("field", exception.getMessage()));
    }

    @ExceptionHandler(CheckInPlaceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request, Map.of());
    }

    @ExceptionHandler(LocationAccuracyTooLowException.class)
    ResponseEntity<ApiErrorResponse> accuracy(HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "LOCATION_ACCURACY_TOO_LOW",
                "Location accuracy must be 100 meters or better", request,
                Map.of("maximumAccuracyMeters", 100));
    }

    @ExceptionHandler(OutsideCheckInRadiusException.class)
    ResponseEntity<ApiErrorResponse> radius(HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "OUTSIDE_CHECK_IN_RADIUS",
                "Move within the allowed check-in radius", request,
                Map.of("allowedRadiusMeters", 200));
    }

    @ExceptionHandler(CheckInRateLimitedException.class)
    ResponseEntity<ApiErrorResponse> rateLimited(
            CheckInRateLimitedException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .body(body("CHECK_IN_RATE_LIMITED", "Daily check-in limit exceeded", request,
                        Map.of("retryAfterSeconds", exception.retryAfterSeconds())));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "Stamp service is temporarily unavailable", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request, Map<String, Object> details) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(body(code, message, request, details));
    }

    private ApiErrorResponse body(
            String code, String message, HttpServletRequest request, Map<String, Object> details) {
        var value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        var requestId = value instanceof String existing && !existing.isBlank()
                ? existing
                : UUID.randomUUID().toString();
        return new ApiErrorResponse("1.2", code, message, requestId, details);
    }
}
