package com.yrootlab.onmaru.identity.guest;

import java.time.Instant;
import java.util.UUID;

public interface GuestOwnershipStore {

    GuestGrantClaimStatus claimGuestExploration(
            UUID memberId,
            UUID explorationId,
            String guestTokenHash,
            Instant grantExpiresAt,
            Instant now);

    boolean memberOwnsOrHasGrant(UUID memberId, UUID explorationId, Instant now);
}
