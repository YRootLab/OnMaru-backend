package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;

import java.time.Duration;
import java.util.Map;

/** Journey-only compatibility aliases for the existing rate-limit error envelope. */
public record JourneyRateLimitedResponse(
        String schemaVersion,
        String code,
        String message,
        String requestId,
        Map<String, Object> details,
        String classification,
        int status) {

    public static JourneyRateLimitedResponse of(String requestId, Duration retryAfter) {
        var error = ApiErrorResponse.of(
                ApiErrorCode.RATE_LIMITED, requestId, Map.of("retryAfterMs", retryAfter.toMillis()));
        return new JourneyRateLimitedResponse(
                error.schemaVersion(), error.code(), error.message(), error.requestId(), error.details(),
                ApiErrorCode.RATE_LIMITED.name(), ApiErrorCode.RATE_LIMITED.status().value());
    }
}
