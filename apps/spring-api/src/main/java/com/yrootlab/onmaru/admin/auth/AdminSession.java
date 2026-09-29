package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.UUID;

public record AdminSession(
        String tokenHash,
        UUID adminId,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        String rotatedToHash) {

    public boolean activeAt(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public AdminSession rotatedTo(String replacementHash, Instant now) {
        return new AdminSession(tokenHash, adminId, createdAt, now, expiresAt, now, replacementHash);
    }
}
