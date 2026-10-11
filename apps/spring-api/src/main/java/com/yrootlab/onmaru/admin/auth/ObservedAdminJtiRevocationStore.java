package com.yrootlab.onmaru.admin.auth;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Instant;
import java.util.UUID;

public final class ObservedAdminJtiRevocationStore implements AdminJtiRevocationStore {

    private final AdminJtiRevocationStore delegate;
    private final MeterRegistry registry;

    public ObservedAdminJtiRevocationStore(AdminJtiRevocationStore delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    public AdminJtiRevocationStore delegate() {
        return delegate;
    }

    @Override
    public void revoke(UUID adminId, String jti, Instant expiresAt) {
        Timer.Sample sample = Timer.start(registry);
        String result = "success";
        try {
            delegate.revoke(adminId, jti, expiresAt);
        } catch (RuntimeException exception) {
            result = "error";
            throw exception;
        } finally {
            sample.stop(registry.timer("onmaru.admin.jwt.revocation.write", "result", result));
        }
    }

    @Override
    public boolean isRevoked(String jti, Instant now) {
        Timer.Sample sample = Timer.start(registry);
        String result = "error";
        try {
            boolean revoked = delegate.isRevoked(jti, now);
            result = revoked ? "revoked" : "clear";
            return revoked;
        } finally {
            sample.stop(registry.timer("onmaru.admin.jwt.revocation.lookup", "result", result));
        }
    }

    @Override
    public int deleteExpired(Instant now, int limit) {
        try {
            int deleted = delegate.deleteExpired(now, limit);
            registry.counter("onmaru.admin.jwt.revocation.cleanup", "result", "success").increment();
            registry.counter("onmaru.admin.jwt.revocation.cleanup.deleted").increment(deleted);
            return deleted;
        } catch (RuntimeException exception) {
            registry.counter("onmaru.admin.jwt.revocation.cleanup", "result", "error").increment();
            throw exception;
        }
    }
}
