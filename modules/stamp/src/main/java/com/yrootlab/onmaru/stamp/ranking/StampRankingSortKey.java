package com.yrootlab.onmaru.stamp.ranking;

import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

public record StampRankingSortKey(
        int stampCount,
        int visitedRegionCount,
        Instant lastAwardedAt,
        UUID publicId) implements Comparable<StampRankingSortKey> {

    @Override
    public int compareTo(StampRankingSortKey other) {
        int result = Integer.compare(other.stampCount, stampCount);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(other.visitedRegionCount, visitedRegionCount);
        if (result != 0) {
            return result;
        }
        result = Comparator.nullsLast(Comparator.<Instant>naturalOrder())
                .compare(lastAwardedAt, other.lastAwardedAt);
        if (result != 0) {
            return result;
        }
        return publicId.toString().compareTo(other.publicId.toString());
    }
}
