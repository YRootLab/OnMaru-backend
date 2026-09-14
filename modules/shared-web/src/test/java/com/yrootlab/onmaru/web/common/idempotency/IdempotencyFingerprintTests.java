package com.yrootlab.onmaru.web.common.idempotency;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyFingerprintTests {

    @Test
    void createsStableFingerprintForSameOperationScopeAndPayload() {
        var first = IdempotencyFingerprint.sha256("post", "/api/v1/explorations", "member-1",
                Map.of("query", "전주 한옥"));
        var second = IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "member-1",
                Map.of("query", "전주 한옥"));

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64);
    }

    @Test
    void changesFingerprintWhenPayloadChanges() {
        var first = IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "member-1",
                Map.of("query", "전주 한옥"));
        var second = IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "member-1",
                Map.of("query", "서울 한옥"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void changesFingerprintWhenOperationScopeChanges() {
        var first = IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "member-1",
                Map.of("query", "전주 한옥"));
        var second = IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "member-2",
                Map.of("query", "전주 한옥"));

        assertThat(first).isNotEqualTo(second);
    }
}
