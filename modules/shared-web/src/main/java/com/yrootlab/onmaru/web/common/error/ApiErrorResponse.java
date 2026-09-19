package com.yrootlab.onmaru.web.common.error;

import java.util.Map;

public record ApiErrorResponse(
        String schemaVersion,
        String code,
        String message,
        String requestId,
        Map<String, Object> details) {

    public static ApiErrorResponse of(ApiErrorCode code, String requestId, Map<String, Object> details) {
        return new ApiErrorResponse("1.2", code.name(), code.message(), requestId, details == null ? Map.of() : details);
    }
}
