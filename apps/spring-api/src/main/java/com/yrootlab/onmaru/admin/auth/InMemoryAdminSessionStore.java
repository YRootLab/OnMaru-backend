package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAdminSessionStore implements AdminSessionStore {

    private final Map<String, AdminSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void save(AdminSession session) {
        sessions.put(session.tokenHash(), session);
    }

    @Override
    public Optional<AdminSession> findByTokenHash(String tokenHash) {
        return Optional.ofNullable(sessions.get(tokenHash));
    }

    @Override
    public void replace(AdminSession current, AdminSession replacement) {
        sessions.put(current.tokenHash(), current);
        sessions.put(replacement.tokenHash(), replacement);
    }

    @Override
    public void revoke(String tokenHash, Instant revokedAt) {
        sessions.computeIfPresent(tokenHash, (ignored, session) -> new AdminSession(
                session.tokenHash(), session.adminId(), session.createdAt(), session.lastSeenAt(),
                session.expiresAt(), revokedAt, session.rotatedToHash()));
    }
}
