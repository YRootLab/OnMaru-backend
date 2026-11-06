package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class InternalAiProgressVerifier {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Base64.Decoder BASE64 = Base64.getUrlDecoder();

    private final SecretProvider secretProvider;
    private final String secretName;
    private final Clock clock;

    public InternalAiProgressVerifier(SecretProvider secretProvider, String secretName, Clock clock) {
        this.secretProvider = secretProvider;
        this.secretName = secretName;
        this.clock = clock;
    }

    public boolean verifyToken(String token, UUID expectedRunId, String expectedPlanHash) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return false;
        }

        try {
            String signingInput = parts[0] + "." + parts[1];
            String signature = parts[2];
            String secret = secretProvider.get(secretName).current();

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expectedSig = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
            byte[] actualSig = BASE64.decode(signature);

            if (!MessageDigest.isEqual(expectedSig, actualSig)) {
                return false;
            }

            JsonNode claims = OBJECT_MAPPER.readTree(BASE64.decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (Instant.ofEpochSecond(exp).isBefore(clock.instant())) {
                return false;
            }

            String runIdStr = claims.path("runId").asText(null);
            if (runIdStr == null || !expectedRunId.toString().equalsIgnoreCase(runIdStr)) {
                return false;
            }

            if (expectedPlanHash != null) {
                String tokenPlanHash = claims.path("planHash").asText(null);
                if (tokenPlanHash != null && !expectedPlanHash.equalsIgnoreCase(tokenPlanHash)) {
                    return false;
                }
            }

            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
