package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAdminJtiRevocationStore implements AdminJtiRevocationStore {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();

    @Override
    public void revoke(String jti, Instant expiresAt) {
        if (jti == null || jti.isBlank() || expiresAt == null) {
            throw new IllegalArgumentException("jti and expiresAt are required");
        }
        revoked.compute(jti, (ignored, existing) -> existing == null || existing.isBefore(expiresAt)
                ? expiresAt
                : existing);
    }

    @Override
    public boolean isRevoked(String jti, Instant now) {
        Instant expiresAt = revoked.get(jti);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(now)) {
            revoked.remove(jti, expiresAt);
            return false;
        }
        return true;
    }
}
