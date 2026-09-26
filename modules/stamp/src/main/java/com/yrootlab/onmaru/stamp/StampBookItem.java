package com.yrootlab.onmaru.stamp;

import java.time.Instant;

public record StampBookItem(
        String code,
        String name,
        StampRarity rarity,
        String regionGroup,
        String conditionLabel,
        String description,
        String sealText,
        String iconName,
        String color,
        int sortOrder,
        boolean collected,
        Instant collectedAt,
        String triggerPlaceId) {
}
