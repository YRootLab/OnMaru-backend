package com.yrootlab.onmaru.stamp.ranking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StampRankingStore {
    List<StampRankingEntry> leaderboard(int limit);

    StampRankingStatus status(UUID memberId);

    StampRankingStatus participate(UUID memberId, StampRankingIdentity identity, Instant now);

    StampRankingStatus withdraw(UUID memberId, Instant now);
}
