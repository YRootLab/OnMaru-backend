package com.yrootlab.onmaru.stamp.ranking;

public record StampRankingStatus(
        boolean participating,
        String publicNickname,
        StampRankingNicknameType nicknameType,
        Integer rank,
        int participantCount,
        int stampCount,
        int visitedRegionCount,
        int completionRate) {
}
