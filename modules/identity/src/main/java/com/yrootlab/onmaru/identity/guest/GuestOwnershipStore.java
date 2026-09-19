package com.yrootlab.onmaru.identity.guest;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GuestOwnershipStore {

    GuestGrantClaimStatus claimGuestExploration(
            UUID memberId,
            UUID explorationId,
            String guestTokenHash,
            Instant grantExpiresAt,
            Instant now);

    boolean memberOwnsOrHasGrant(UUID memberId, UUID explorationId, Instant now);

    void saveGuest(UUID guestId, String tokenHash, Instant expiresAt);

    Optional<UUID> findActiveGuest(String tokenHash, Instant now);

    void saveGuestExploration(UUID explorationId, UUID guestId, Instant expiresAt);

    void clearGuests();
}
