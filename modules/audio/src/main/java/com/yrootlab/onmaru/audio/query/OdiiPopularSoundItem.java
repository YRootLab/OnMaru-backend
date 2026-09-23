package com.yrootlab.onmaru.audio.query;

public record OdiiPopularSoundItem(
        int rank,
        long score,
        long playCount,
        long saveCount,
        OdiiStorySummary story) {
}
