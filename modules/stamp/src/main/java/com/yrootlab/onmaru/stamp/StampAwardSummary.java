package com.yrootlab.onmaru.stamp;

import java.time.Instant;

public record StampAwardSummary(
        String code,
        String name,
        String sealText,
        StampRarity rarity,
        Instant collectedAt) {
}
