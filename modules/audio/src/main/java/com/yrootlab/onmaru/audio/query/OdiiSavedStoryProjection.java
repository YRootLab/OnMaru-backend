package com.yrootlab.onmaru.audio.query;

public record OdiiSavedStoryProjection(
        String storyId,
        String spotId,
        String title,
        String placeId,
        Integer durationSeconds) {
}
