package com.yrootlab.onmaru.web.moderation.queue;

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
final class OperatorAuthenticationExceptionHandler {

    @ExceptionHandler(OperatorAuthenticationRequiredException.class)
    ResponseEntity<ApiErrorResponse> authenticationRequired(HttpServletRequest request) {
        return response(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication is required.", request);
    }

    @ExceptionHandler(OperatorForbiddenException.class)
    ResponseEntity<ApiErrorResponse> forbidden(HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "OPERATOR_FORBIDDEN", "Operator access is forbidden.", request);
    }

    private ResponseEntity<ApiErrorResponse> response(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, message, requestId(request), Map.of()));
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }
}
