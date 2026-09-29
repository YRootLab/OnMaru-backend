package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.Optional;

public interface AdminSessionStore {

    void save(AdminSession session);

    Optional<AdminSession> findByTokenHash(String tokenHash);

    void replace(AdminSession current, AdminSession replacement);

    void revoke(String tokenHash, Instant revokedAt);
}
