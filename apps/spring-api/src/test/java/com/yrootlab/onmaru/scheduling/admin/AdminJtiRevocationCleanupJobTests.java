package com.yrootlab.onmaru.scheduling.admin;

import com.yrootlab.onmaru.admin.auth.AdminJtiRevocationStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AdminJtiRevocationCleanupJobTests {

    @Test
    void repeatsFullBatchesAndStopsAtConfiguredMaximum() {
        AtomicInteger calls = new AtomicInteger();
        AdminJtiRevocationStore store = new AdminJtiRevocationStore() {
            public void revoke(UUID adminId, String jti, Instant expiresAt) { }
            public boolean isRevoked(String jti, Instant now) { return false; }
            public int deleteExpired(Instant now, int limit) {
                calls.incrementAndGet();
                return limit;
            }
        };
        var job = new AdminJtiRevocationCleanupJob(
                store, Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC), 50, 3);

        assertThat(job.runOnce()).isEqualTo(150);
        assertThat(calls).hasValue(3);
    }
}
