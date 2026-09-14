package com.yrootlab.onmaru.catalog.editorial;

public record MonthlyHanokPlacementDraft(
        MonthlyHanokSlot slot,
        String reason,
        String placeId) {
}
