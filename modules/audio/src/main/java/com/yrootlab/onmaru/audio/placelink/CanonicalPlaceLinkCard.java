package com.yrootlab.onmaru.audio.placelink;

public record CanonicalPlaceLinkCard(
        String placeId,
        String name,
        String category,
        String regionName,
        String thumbnailUrl,
        boolean savedByMe) {
}
