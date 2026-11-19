package com.yrootlab.onmaru.web.mapcollab;

import com.yrootlab.onmaru.mapcollab.domain.exception.MapCollabException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RateLimitedException;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(basePackages = "com.yrootlab.onmaru.web.mapcollab")
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class MapCollabExceptionHandler {

    @ExceptionHandler(MapCollabException.class)
    public ResponseEntity<ApiErrorResponse> handleMapCollabException(MapCollabException e, HttpServletRequest request) {
        var response = ResponseEntity.status(e.getStatusCode());
        if (e instanceof RateLimitedException rle) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(rle.getRetryAfterSeconds()));
        }
        return response.body(new ApiErrorResponse(
                "1.2",
                e.getCode(),
                e.getMessage(),
                requestId(request),
                e.getDetails()
        ));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiErrorResponse> handleValidationError(Exception e, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse(
                "1.2",
                "VALIDATION_ERROR",
                e.getMessage() != null ? e.getMessage() : "validation error",
                requestId(request),
                Map.of()
        ));
    }

    private static String requestId(HttpServletRequest request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        var fromAttribute = request.getAttribute("requestId");
        if (fromAttribute instanceof String reqId && !reqId.isBlank()) {
            return reqId;
        }
        var fromHeader = request.getHeader("X-Request-Id");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        return UUID.randomUUID().toString();
    }
}
