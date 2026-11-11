package com.yrootlab.onmaru.mapcollab.port;

public record CatalogPlaceInfo(
        String publicId,
        String name,
        double latitude,
        double longitude) {
}
