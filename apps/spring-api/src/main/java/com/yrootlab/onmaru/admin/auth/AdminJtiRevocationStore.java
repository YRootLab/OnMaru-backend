package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;

/** Stores revoked access-token JTIs until their natural expiry. */
public interface AdminJtiRevocationStore {

    void revoke(String jti, Instant expiresAt);

    boolean isRevoked(String jti, Instant now);
}
