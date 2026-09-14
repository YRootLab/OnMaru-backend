package com.yrootlab.onmaru.web.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

public final class IdempotencyFingerprint {

    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private IdempotencyFingerprint() {
    }

    public static String sha256(String method, String path, String operationScope, Object payload) {
        var envelope = Map.of(
                "method", normalizeRequired(method, "method").toUpperCase(Locale.ROOT),
                "path", normalizeRequired(path, "path"),
                "operationScope", normalizeRequired(operationScope, "operationScope"),
                "payload", payload == null ? Map.of() : payload);
        return sha256(canonicalJson(envelope));
    }

    private static String normalizeRequired(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static byte[] canonicalJson(Object value) {
        try {
            return CANONICAL_MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("idempotency payload is not serializable", exception);
        }
    }

    private static String sha256(byte[] value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
