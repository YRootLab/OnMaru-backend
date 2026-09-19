package com.yrootlab.onmaru.identity.guest;

import com.yrootlab.onmaru.identity.oauth.TokenHasher;

import java.time.Clock;
import java.time.Duration;

public final class GuestGrantService {

    private static final Duration GRANT_TTL = Duration.ofMinutes(10);

    private final GuestOwnershipStore store;
    private final TokenHasher tokenHasher;
    private final Clock clock;

    public GuestGrantService(GuestOwnershipStore store, TokenHasher tokenHasher, Clock clock) {
        this.store = store;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
    }

    public GuestGrantClaimResult claim(GuestGrantClaimCommand command) {
        var now = clock.instant();
        var expiresAt = now.plus(GRANT_TTL);
        var status = store.claimGuestExploration(
                command.memberId(),
                command.explorationId(),
                tokenHasher.hash(command.guestToken()),
                expiresAt,
                now);
        return switch (status) {
            case CLAIMED -> new GuestGrantClaimResult(command.memberId(), command.explorationId(), expiresAt);
            case NOT_FOUND -> throw new GuestGrantNotFoundException();
            case ALREADY_CLAIMED -> throw new GuestGrantAlreadyClaimedException();
        };
    }

    public boolean canAccess(MemberExplorationAccess access) {
        return store.memberOwnsOrHasGrant(access.memberId(), access.explorationId(), clock.instant());
    }
}
