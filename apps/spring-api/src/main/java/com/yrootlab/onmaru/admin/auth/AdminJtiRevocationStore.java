package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.UUID;

/** Stores revoked access-token JTIs until their natural expiry. */
public interface AdminJtiRevocationStore {

    void revoke(UUID adminId, String jti, Instant expiresAt);

    boolean isRevoked(String jti, Instant now);

    int deleteExpired(Instant now, int limit);
}
