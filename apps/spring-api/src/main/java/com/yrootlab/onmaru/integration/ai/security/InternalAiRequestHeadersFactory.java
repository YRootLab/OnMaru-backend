package com.yrootlab.onmaru.integration.ai.security;

import java.util.LinkedHashMap;
import java.util.Map;

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

    private static String traceparent(String traceId) {
        return "00-" + traceId + "-0000000000000000-01";
    }
}
