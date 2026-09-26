package com.yrootlab.onmaru.stamp.ranking;

import java.util.UUID;

public record StampRankingEntry(
        int rank,
        UUID publicId,
        String publicNickname,
        StampRankingNicknameType nicknameType,
        int stampCount,
        int visitedRegionCount,
        int completionRate) {
}
