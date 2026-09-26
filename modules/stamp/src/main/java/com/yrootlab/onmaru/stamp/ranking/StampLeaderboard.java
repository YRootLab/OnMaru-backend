package com.yrootlab.onmaru.stamp.ranking;

import java.time.Instant;
import java.util.List;

public record StampLeaderboard(Instant generatedAt, List<StampRankingEntry> entries) {
    public StampLeaderboard {
        entries = List.copyOf(entries);
    }
}
