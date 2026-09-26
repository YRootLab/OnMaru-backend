package com.yrootlab.onmaru.stamp;

public record StampDefinition(
        String code,
        String name,
        String description,
        String conditionLabel,
        String sealText,
        String iconName,
        String color,
        StampRarity rarity,
        StampConditionType conditionType,
        Integer requiredCount,
        String regionGroup,
        int sortOrder,
        boolean active) {
}
