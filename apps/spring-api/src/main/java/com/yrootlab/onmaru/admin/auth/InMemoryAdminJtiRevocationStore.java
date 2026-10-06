package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

public final class InMemoryAdminJtiRevocationStore implements AdminJtiRevocationStore {

    private final Map<String, Instant> revoked = new ConcurrentHashMap<>();
    private final AdminJtiHasher hasher = new AdminJtiHasher();

    @Override
    public void revoke(UUID adminId, String jti, Instant expiresAt) {
        if (adminId == null || expiresAt == null) {
            throw new IllegalArgumentException("adminId and expiresAt are required");
        }
        revoked.compute(hasher.hash(jti), (ignored, existing) -> existing == null || existing.isBefore(expiresAt)
                ? expiresAt
                : existing);
    }

    @Override
    public boolean isRevoked(String jti, Instant now) {
        String hash = hasher.hash(jti);
        Instant expiresAt = revoked.get(hash);
        if (expiresAt == null) {
            return false;
        }
        if (!expiresAt.isAfter(now)) {
            revoked.remove(hash, expiresAt);
            return false;
        }
        return true;
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        if (now == null || limit < 1) {
            throw new IllegalArgumentException("now and positive limit are required");
        }
        int deleted = 0;
        for (var entry : revoked.entrySet()) {
            if (deleted >= limit) {
                break;
            }
            if (!entry.getValue().isAfter(now) && revoked.remove(entry.getKey(), entry.getValue())) {
                deleted++;
            }
        }
        return deleted;
    }
}
