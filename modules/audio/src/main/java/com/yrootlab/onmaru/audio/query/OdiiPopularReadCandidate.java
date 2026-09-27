package com.yrootlab.onmaru.audio.query;

public record OdiiPopularReadCandidate(
        OdiiStoryProjection story,
        long playCount,
        long saveCount) {

    public long score() {
        return 2 * playCount + saveCount;
    }
}
