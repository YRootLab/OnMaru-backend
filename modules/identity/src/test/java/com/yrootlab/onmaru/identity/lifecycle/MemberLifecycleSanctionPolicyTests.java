package com.yrootlab.onmaru.identity.lifecycle;

import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class MemberLifecycleSanctionPolicyTests {

    @Test
    void hidesMemberSessionWhenAccessPolicyRejectsMember() {
        var now = Instant.parse("2026-09-29T00:00:00Z");
        var store = new InMemoryIdentityStore();
        var lifecycle = new MemberLifecycleService(
                store, new TokenHasher("test-secret"), Clock.fixed(now, ZoneOffset.UTC),
                MemberAccessPolicy.rejectAll());
        var memberId = store.createMember(now);
        store.saveSession(new com.yrootlab.onmaru.identity.oauth.SessionRecord(
                new TokenHasher("test-secret").hash("session"), memberId, now, now, now.plusSeconds(60)));

        assertThat(lifecycle.currentMember("session")).isEmpty();
    }
}
