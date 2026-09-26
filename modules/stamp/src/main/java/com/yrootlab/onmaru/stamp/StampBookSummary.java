package com.yrootlab.onmaru.stamp;

public record StampBookSummary(
        int collectedCount,
        int totalCount,
        int visitedRegionCount,
        int requiredRegionCount,
        int completionRate) {
}
