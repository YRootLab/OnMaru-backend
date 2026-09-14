package com.yrootlab.onmaru.identity.guest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryGuestOwnershipStore implements GuestOwnershipStore {

    private final Map<UUID, GuestRecord> guests = new HashMap<>();
    private final Map<UUID, GuestExplorationRecord> explorations = new HashMap<>();
    private final Map<UUID, MemberGrantRecord> grantsByExploration = new HashMap<>();

    @Override
    public synchronized GuestGrantClaimStatus claimGuestExploration(
            UUID memberId,
            UUID explorationId,
            String guestTokenHash,
            Instant grantExpiresAt,
            Instant now) {
        var exploration = explorations.get(explorationId);
        if (exploration == null || !exploration.expiresAt().isAfter(now)) {
            return GuestGrantClaimStatus.NOT_FOUND;
        }
        var guest = guests.get(exploration.guestId());
        if (guest == null || !guest.expiresAt().isAfter(now) || !guest.tokenHash().equals(guestTokenHash)) {
            return GuestGrantClaimStatus.NOT_FOUND;
        }
        if (grantsByExploration.containsKey(explorationId)) {
            return GuestGrantClaimStatus.ALREADY_CLAIMED;
        }
        grantsByExploration.put(explorationId, new MemberGrantRecord(memberId, explorationId, grantExpiresAt));
        return GuestGrantClaimStatus.CLAIMED;
    }

    @Override
    public synchronized boolean memberOwnsOrHasGrant(UUID memberId, UUID explorationId, Instant now) {
        var grant = grantsByExploration.get(explorationId);
        return grant != null && grant.memberId().equals(memberId) && grant.expiresAt().isAfter(now);
    }

    void saveGuest(UUID guestId, String tokenHash, Instant expiresAt) {
        guests.put(guestId, new GuestRecord(guestId, tokenHash, expiresAt));
    }

    void saveGuestExploration(UUID explorationId, UUID guestId, Instant expiresAt) {
        explorations.put(explorationId, new GuestExplorationRecord(explorationId, guestId, expiresAt));
    }

    void saveMemberGrant(UUID memberId, UUID explorationId, Instant expiresAt) {
        grantsByExploration.put(explorationId, new MemberGrantRecord(memberId, explorationId, expiresAt));
    }

    private record GuestRecord(UUID id, String tokenHash, Instant expiresAt) {
    }

    private record GuestExplorationRecord(UUID explorationId, UUID guestId, Instant expiresAt) {
    }

    private record MemberGrantRecord(UUID memberId, UUID explorationId, Instant expiresAt) {
    }
}
