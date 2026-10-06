package com.yrootlab.onmaru.admin.auth;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ObservedAdminJtiRevocationStoreTests {

    @Test
    void recordsBoundedResultTagsForLookupWriteAndCleanup() {
        var registry = new SimpleMeterRegistry();
        var observed = new ObservedAdminJtiRevocationStore(new InMemoryAdminJtiRevocationStore(), registry);
        Instant now = Instant.parse("2026-10-06T00:00:00Z");

        observed.revoke(UUID.randomUUID(), "jti", now.plusSeconds(60));
        assertThat(observed.isRevoked("jti", now)).isTrue();
        assertThat(observed.deleteExpired(now.plusSeconds(60), 100)).isEqualTo(1);

        assertThat(registry.get("onmaru.admin.jwt.revocation.write").tag("result", "success").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("onmaru.admin.jwt.revocation.lookup").tag("result", "revoked").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("onmaru.admin.jwt.revocation.cleanup.deleted").counter().count())
                .isEqualTo(1);
    }
}
