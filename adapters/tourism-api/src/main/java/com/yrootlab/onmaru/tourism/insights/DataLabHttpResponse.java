package com.yrootlab.onmaru.tourism.insights;

/** Minimal transport result; retry-after is normalized to seconds when present. */
public record DataLabHttpResponse(int statusCode, String body, Long retryAfterSeconds) {
}
