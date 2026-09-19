package com.yrootlab.onmaru.identity.lifecycle;

import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MemberLifecycleServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    private static final TokenHasher HASHER = new TokenHasher("test-pepper");

    @Test
    void logoutImmediatelyInvalidatesSession() {
        var store = new InMemoryIdentityStore();
        var memberId = store.createMember(CLOCK.instant());
        store.saveSession(new SessionRecord(
                HASHER.hash("session-token"),
                memberId,
                CLOCK.instant(),
                CLOCK.instant(),
                CLOCK.instant().plusSeconds(3600)));
        var service = new MemberLifecycleService(store, HASHER, CLOCK);

        assertThat(service.currentMember("session-token")).isPresent();

        service.logout("session-token");

        assertThat(service.currentMember("session-token")).isEmpty();
    }

    @Test
    void deletionMarksMemberDeletingRevokesSessionAndBlocksLateWrites() {
        var store = new InMemoryIdentityStore();
        var memberId = store.createMember(CLOCK.instant());
        store.saveSession(new SessionRecord(
                HASHER.hash("session-token"),
                memberId,
                CLOCK.instant(),
                CLOCK.instant(),
                CLOCK.instant().plusSeconds(3600)));
        var service = new MemberLifecycleService(store, HASHER, CLOCK);

        var result = service.requestDeletion("session-token");

        assertThat(result.status()).isEqualTo(MemberLifecycleStatus.DELETING);
        assertThat(service.currentMember("session-token")).isEmpty();
        assertThat(service.allowsLateWrite(memberId)).isFalse();
    }
}
