package com.yrootlab.onmaru.integration.ai.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class InternalAiRequestHeadersFactory {

    private final InternalAiTokenSigner tokenSigner;

    public InternalAiRequestHeadersFactory(InternalAiTokenSigner tokenSigner) {
        this.tokenSigner = tokenSigner;
    }

    public Map<String, String> headersFor(InternalAiTokenRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + tokenSigner.sign(request));
        headers.put("X-Request-Id", request.requestId());
        headers.put("X-Run-Id", request.runId());
        headers.put("X-Revision", request.revision());
        headers.put("traceparent", traceparent(request.traceId()));
        return headers;
    }

    /** A fresh non-zero span per call; AI rejects an all-zero span as CORRELATION_INVALID. */
    private static String traceparent(String traceId) {
        String span;
        do {
            span = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        } while (span.equals("0".repeat(16)));
        return "00-" + traceId + "-" + span + "-01";
    }
}
