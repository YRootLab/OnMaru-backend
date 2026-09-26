package com.yrootlab.onmaru.stamp.ranking;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class StampRankingService {
    private final StampRankingStore store;
    private final StampRankingIdentityGenerator identities;
    private final Clock clock;

    public StampRankingService(StampRankingStore store, StampRankingIdentityGenerator identities, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.identities = Objects.requireNonNull(identities);
        this.clock = Objects.requireNonNull(clock);
    }

    public StampLeaderboard leaderboard(int limit) {
        if (limit < 1 || limit > 100) {
            throw new StampRankingInputInvalidException("limit");
        }
        return new StampLeaderboard(clock.instant(), store.leaderboard(limit));
    }

    public StampRankingStatus status(UUID memberId) {
        return store.status(memberId);
    }

    public StampRankingStatus update(UUID memberId, boolean participating) {
        if (!participating) {
            return store.withdraw(memberId, clock.instant());
        }
        var current = store.status(memberId);
        if (current.participating()) {
            return current;
        }
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return store.participate(memberId, identities.generate(), clock.instant());
            } catch (StampRankingIdentityConflictException ignored) {
                // A fresh anonymous identity is generated on the next attempt.
            }
        }
        throw new IllegalStateException("Failed to allocate anonymous ranking identity");
    }
}
