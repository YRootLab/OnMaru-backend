package com.yrootlab.onmaru.web.common.cursor;

import java.time.Instant;
import java.util.Map;

public record CursorPayload(String scope, Map<String, Object> claims, Instant expiresAt) {

    public CursorPayload {
        if (scope == null || scope.isBlank()) {
            throw new IllegalArgumentException("scope must not be blank");
        }
        claims = claims == null ? Map.of() : Map.copyOf(claims);
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
    }
}
