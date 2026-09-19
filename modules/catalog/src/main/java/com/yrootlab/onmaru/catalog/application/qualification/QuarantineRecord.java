package com.yrootlab.onmaru.catalog.application.qualification;

import java.util.Map;

public record QuarantineRecord(
        String recordKey,
        String errorCode,
        String payloadHash,
        Map<String, String> redactedPayload
) {
}
