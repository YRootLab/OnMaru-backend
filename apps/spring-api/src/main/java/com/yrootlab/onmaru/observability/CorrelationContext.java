package com.yrootlab.onmaru.observability;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;

record CorrelationContext(String requestId, String traceId, String runId, String revision) {

    private static final Pattern TRACEPARENT_PATTERN = Pattern.compile(
            "^[\\da-f]{2}-([\\da-f]{32})-[\\da-f]{16}-[\\da-f]{2}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    static CorrelationContext from(HttpServletRequest request) {
        return new CorrelationContext(
                requestIdFrom(request).orElseGet(CorrelationContext::randomUuid),
                firstHeader(request, "traceparent")
                        .flatMap(CorrelationContext::traceIdFromTraceparent)
                        .orElseGet(() -> randomHex(16)),
                firstHeader(request, "X-Run-Id").orElse("unknown"),
                firstHeader(request, "X-Revision").orElse("unknown"));
    }

    private static Optional<String> requestIdFrom(HttpServletRequest request) {
        Object requestId = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (requestId instanceof String value && !value.isBlank()) {
            return Optional.of(value);
        }
        return firstHeader(request, RequestIdFilter.HEADER);
    }

    private static Optional<String> firstHeader(HttpServletRequest request, String name) {
        return Optional.ofNullable(request.getHeader(name)).filter(value -> !value.isBlank());
    }

    private static Optional<String> traceIdFromTraceparent(String traceparent) {
        var matcher = TRACEPARENT_PATTERN.matcher(traceparent);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        var traceId = matcher.group(1);
        if ("00000000000000000000000000000000".equals(traceId)) {
            return Optional.empty();
        }
        return Optional.of(traceId);
    }

    private static String randomUuid() {
        return java.util.UUID.randomUUID().toString();
    }

    private static String randomHex(int bytes) {
        byte[] randomBytes = new byte[bytes];
        RANDOM.nextBytes(randomBytes);
        return HEX.formatHex(randomBytes);
    }
}
