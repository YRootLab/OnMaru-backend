package com.yrootlab.onmaru.identity.guest;

import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestGrantServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    private static final TokenHasher HASHER = new TokenHasher("test-pepper");

    @Test
    void rejectsOtherActorGuestTokenAndExpiredGuestOwnershipAsNotFound() {
        var store = new InMemoryGuestOwnershipStore();
        var service = new GuestGrantService(store, HASHER, CLOCK);
        var memberId = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        store.saveGuest(guestId, HASHER.hash("guest-token"), CLOCK.instant().plus(Duration.ofHours(1)));
        store.saveGuestExploration(explorationId, guestId, CLOCK.instant().plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> service.claim(new GuestGrantClaimCommand(
                memberId,
                explorationId,
                "other-guest-token")))
                .isInstanceOf(GuestGrantNotFoundException.class);

        var expiredExplorationId = UUID.randomUUID();
        store.saveGuestExploration(expiredExplorationId, guestId, CLOCK.instant().minusSeconds(1));
        assertThatThrownBy(() -> service.claim(new GuestGrantClaimCommand(
                memberId,
                expiredExplorationId,
                "guest-token")))
                .isInstanceOf(GuestGrantNotFoundException.class);
    }

    @Test
    void successfulClaimCanBeUsedOnceOnly() {
        var store = new InMemoryGuestOwnershipStore();
        var service = new GuestGrantService(store, HASHER, CLOCK);
        var memberId = UUID.randomUUID();
        var otherMemberId = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        store.saveGuest(guestId, HASHER.hash("guest-token"), CLOCK.instant().plus(Duration.ofHours(1)));
        store.saveGuestExploration(explorationId, guestId, CLOCK.instant().plus(Duration.ofHours(1)));

        var result = service.claim(new GuestGrantClaimCommand(memberId, explorationId, "guest-token"));

        assertThat(result.memberId()).isEqualTo(memberId);
        assertThat(result.explorationId()).isEqualTo(explorationId);
        assertThat(service.canAccess(new MemberExplorationAccess(memberId, explorationId))).isTrue();
        assertThat(service.canAccess(new MemberExplorationAccess(otherMemberId, explorationId))).isFalse();
        assertThatThrownBy(() -> service.claim(new GuestGrantClaimCommand(memberId, explorationId, "guest-token")))
                .isInstanceOf(GuestGrantAlreadyClaimedException.class);
        assertThatThrownBy(() -> service.claim(new GuestGrantClaimCommand(otherMemberId, explorationId, "guest-token")))
                .isInstanceOf(GuestGrantAlreadyClaimedException.class);
    }

    @Test
    void expiredGrantNoLongerAuthorizesMember() {
        var store = new InMemoryGuestOwnershipStore();
        var service = new GuestGrantService(store, HASHER, CLOCK);
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        store.saveMemberGrant(memberId, explorationId, CLOCK.instant().minusSeconds(1));

        assertThat(service.canAccess(new MemberExplorationAccess(memberId, explorationId))).isFalse();
    }
}
