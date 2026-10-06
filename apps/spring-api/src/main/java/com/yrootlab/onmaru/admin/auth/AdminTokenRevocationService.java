package com.yrootlab.onmaru.admin.auth;

import java.time.Clock;
import java.time.Instant;

/** Coordinates immediate access-token invalidation without retaining token contents. */
public final class AdminTokenRevocationService {

    private final AdminJtiRevocationStore store;
    private final Clock clock;

    public AdminTokenRevocationService(AdminJtiRevocationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void revoke(AdminAccessToken token) {
        store.revoke(token.principal().id(), token.jti(), token.expiresAt());
    }

    public void revoke(java.util.UUID adminId, String jti, Instant expiresAt) {
        store.revoke(adminId, jti, expiresAt);
    }

    public boolean isRevoked(String jti) {
        return store.isRevoked(jti, clock.instant());
    }
}
