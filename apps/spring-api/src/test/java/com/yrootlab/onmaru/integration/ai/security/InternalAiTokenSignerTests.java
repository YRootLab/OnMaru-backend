package com.yrootlab.onmaru.integration.ai.security;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InternalAiTokenSignerTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void signsShortLivedInternalTokenWithAudienceScopeAndCorrelationClaims() throws Exception {
        InternalAiTokenSigner signer = new InternalAiTokenSigner(
                new FakeSecretProvider(),
                InternalAiTokenProperties.defaults(),
                FIXED_CLOCK);

        String token = signer.sign(InternalAiTokenRequest.forJourneyProposal(
                "req-ai-001",
                "trace-abc",
                "run-ai-001",
                "dataset-2026-09-16",
                Instant.parse("2026-09-15T10:00:20Z")));

        Map<String, Object> header = decodeSegment(token.split("\\.")[0]);
        Map<String, Object> claims = decodeSegment(token.split("\\.")[1]);

        assertThat(header)
                .containsEntry("alg", "HS256")
                .containsEntry("typ", "JWT")
                .containsEntry("kid", "current");
        assertThat(claims)
                .containsEntry("iss", "onmaru-spring")
                .containsEntry("sub", "spring-api")
                .containsEntry("aud", "onmaru-ai")
                .containsEntry("scope", "journey.proposal:write")
                .containsEntry("requestId", "req-ai-001")
                .containsEntry("traceId", "trace-abc")
                .containsEntry("runId", "run-ai-001")
                .containsEntry("revision", "dataset-2026-09-16")
                .containsEntry("deadlineAt", "2026-09-15T10:00:20Z");
        assertThat((Integer) claims.get("iat")).isEqualTo(1789466400);
        assertThat((Integer) claims.get("exp")).isEqualTo(1789466460);
        assertThat((String) claims.get("jti")).isNotBlank();
    }

    @Test
    void buildsInternalRequestHeadersWithoutPuttingBearerTokenInCorrelationHeaders() {
        InternalAiTokenSigner signer = new InternalAiTokenSigner(
                new FakeSecretProvider(),
                InternalAiTokenProperties.defaults(),
                FIXED_CLOCK);
        InternalAiRequestHeadersFactory factory = new InternalAiRequestHeadersFactory(signer);

        Map<String, String> headers = factory.headersFor(InternalAiTokenRequest.forJourneyProposal(
                "req-ai-001",
                "4bf92f3577b34da6a3ce929d0e0e4736",
                "run-ai-001",
                "dataset-2026-09-16",
                Instant.parse("2026-09-15T10:00:20Z")));

        assertThat(headers)
                .containsEntry("X-Request-Id", "req-ai-001")
                .containsEntry("X-Run-Id", "run-ai-001")
                .containsEntry("X-Revision", "dataset-2026-09-16")
                .containsEntry("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01");
        assertThat(headers.get("Authorization")).startsWith("Bearer ");
        assertThat(headers.get("X-Request-Id")).doesNotContain("Bearer");
        assertThat(headers.get("traceparent")).doesNotContain("Bearer");
    }

    private static Map<String, Object> decodeSegment(String segment) throws Exception {
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64(segment));
        return OBJECT_MAPPER.readValue(
                new String(decoded, StandardCharsets.UTF_8),
                new TypeReference<>() {});
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        return value + "=".repeat(4 - remainder);
    }
}
