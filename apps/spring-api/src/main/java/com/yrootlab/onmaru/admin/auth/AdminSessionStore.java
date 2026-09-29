package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.Optional;

public interface AdminSessionStore {

    void save(AdminSession session);

    Optional<AdminSession> findByTokenHash(String tokenHash);

    void replace(AdminSession current, AdminSession replacement);

    void revoke(String tokenHash, Instant revokedAt);

    /**
     * Revokes the rotated descendants of a compromised refresh token.
     * Implementations with no family-specific query support can use the
     * existing forward rotation links.
     */
    default void revokeFamily(AdminSession compromised, Instant revokedAt) {
        AdminSession current = compromised;
        while (current != null) {
            revoke(current.tokenHash(), revokedAt);
            current = current.rotatedToHash() == null
                    ? null
                    : findByTokenHash(current.rotatedToHash()).orElse(null);
        }
    }
}
