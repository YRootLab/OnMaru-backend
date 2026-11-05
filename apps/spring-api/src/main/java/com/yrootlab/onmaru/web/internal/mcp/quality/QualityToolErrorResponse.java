package com.yrootlab.onmaru.web.internal.mcp.quality;

public record QualityToolErrorResponse(
        String code,
        String message,
        boolean retryable
) {
}
