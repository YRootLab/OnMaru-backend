package com.yrootlab.onmaru.integration.ai.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class InternalAiTokenSigner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final SecretProvider secretProvider;
    private final InternalAiTokenProperties properties;
    private final Clock clock;

    public InternalAiTokenSigner(
            SecretProvider secretProvider,
            InternalAiTokenProperties properties,
            Clock clock) {
        this.secretProvider = secretProvider;
        this.properties = properties;
        this.clock = clock;
    }

    public String sign(InternalAiTokenRequest request) {
        Instant now = Instant.now(clock);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");
        header.put("kid", "current");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", properties.issuer());
        claims.put("sub", properties.subject());
        claims.put("aud", properties.audience());
        claims.put("scope", properties.scope());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(properties.timeToLive()).getEpochSecond());
        claims.put("requestId", request.requestId());
        claims.put("traceId", request.traceId());
        claims.put("runId", request.runId());
        claims.put("deadlineAt", request.deadlineAt().toString());

        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        String secret = secretProvider.get(properties.secretName()).current();
        return signingInput + "." + sign(secret, signingInput);
    }

    private static String encodeJson(Map<String, Object> value) {
        try {
            return BASE64.encodeToString(OBJECT_MAPPER.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode internal AI token", exception);
        }
    }

    private static String sign(String secret, String signingInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return BASE64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to sign internal AI token", exception);
        }
    }
}
