package com.yrootlab.onmaru.admin.users;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminSanctionMemberAccessPolicyTests {

    @Test
    void deniesOnlyCurrentlyActiveSanctions() {
        var now = Instant.parse("2026-09-29T00:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var store = new InMemoryAdminSanctionStore();
        var memberId = UUID.randomUUID();
        var adminId = UUID.randomUUID();
        store.create(memberId, adminId, "spam", now.minusSeconds(60), now.plusSeconds(60));
        var policy = new AdminSanctionMemberAccessPolicy(store, clock);

        assertThat(policy.allows(memberId, now)).isFalse();
        assertThat(policy.allows(UUID.randomUUID(), now)).isTrue();
    }
}
