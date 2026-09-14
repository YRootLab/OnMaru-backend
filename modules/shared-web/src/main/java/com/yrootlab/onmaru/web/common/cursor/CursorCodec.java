package com.yrootlab.onmaru.web.common.cursor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public final class CursorCodec {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final CursorSigningKey signingKey;
    private final Clock clock;

    public CursorCodec(ObjectMapper objectMapper, CursorSigningKey signingKey, Clock clock) {
        this.objectMapper = objectMapper;
        this.signingKey = signingKey;
        this.clock = clock;
    }

    public String encode(CursorPayload payload) {
        try {
            var json = objectMapper.writeValueAsBytes(Map.of(
                    "scope", payload.scope(),
                    "claims", payload.claims(),
                    "expiresAt", payload.expiresAt().toString()));
            var encodedPayload = ENCODER.encodeToString(json);
            var encodedSignature = ENCODER.encodeToString(sign(encodedPayload.getBytes(StandardCharsets.UTF_8)));
            return encodedPayload + "." + encodedSignature;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cursor payload is not serializable", exception);
        }
    }

    public CursorPayload decode(String cursor, String expectedScope) {
        try {
            var parts = cursor == null ? new String[0] : cursor.split("\\.", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new CursorInvalidException();
            }
            var payloadBytes = DECODER.decode(parts[0]);
            var suppliedSignature = DECODER.decode(parts[1]);
            var expectedSignature = sign(parts[0].getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(suppliedSignature, expectedSignature)) {
                throw new CursorInvalidException();
            }

            var envelope = objectMapper.readValue(payloadBytes, MAP_TYPE);
            var scope = (String) envelope.get("scope");
            if (!expectedScope.equals(scope)) {
                throw new CursorInvalidException();
            }
            var claims = castClaims(envelope.get("claims"));
            var expiresAt = Instant.parse((String) envelope.get("expiresAt"));
            if (!expiresAt.isAfter(clock.instant())) {
                throw new CursorExpiredException();
            }
            return new CursorPayload(scope, claims, expiresAt);
        } catch (CursorExpiredException | CursorInvalidException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new CursorInvalidException(exception);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castClaims(Object value) {
        if (value instanceof Map<?, ?> claims) {
            return (Map<String, Object>) claims;
        }
        throw new CursorInvalidException();
    }

    private byte[] sign(byte[] value) {
        try {
            var mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey.bytes(), HMAC_ALGORITHM));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("cursor signature cannot be created", exception);
        }
    }
}
